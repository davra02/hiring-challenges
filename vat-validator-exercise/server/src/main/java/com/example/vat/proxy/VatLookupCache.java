package com.example.vat.proxy;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shares lookups: between customers asking for the same number at the same
 * time, and between one question and the next time anyone asks it.
 *
 * <p>
 * One map does both jobs. Each normalized VAT number maps to a future. While
 * the future is incomplete, the lookup is in flight, and whoever else asks
 * waits on it instead of starting another. Once it is complete, it is the
 * cached answer, good until the {@linkplain #timeToLive time to live} of its
 * status runs out. Because one entry plays both parts, there is no moment
 * between "in flight" and "answered" in which a caller finds neither and
 * starts a second call.
 * </p>
 *
 * <p>
 * The future always completes normally. An {@link VatLookupStatus#ERROR} is
 * an answer too, so nobody waiting on the future has an exception to handle.
 * </p>
 *
 * <p>
 * The upstream budget is spent here, and only when a new call is needed. A
 * cached answer or a shared call costs nothing.
 * </p>
 *
 * <p>
 * The map is not bounded and nothing evicts from it. An expired entry is
 * replaced the next time its number is asked for, and otherwise stays.
 * </p>
 */
public class VatLookupCache {

	public VatLookupCache(UpstreamRateLimiter upstreamRateLimiter) {
		this(upstreamRateLimiter, InstantSource.system());
	}

	/**
	 * @param instantSource a wall clock. A time to live is measured in hours,
	 *        so an adjustment to the clock moves an expiry a little and breaks
	 *        nothing.
	 */
	VatLookupCache(
		UpstreamRateLimiter upstreamRateLimiter, InstantSource instantSource) {

		_upstreamRateLimiter = upstreamRateLimiter;
		_instantSource = instantSource;
	}

	/**
	 * How long an answer with this status is served before the registry is
	 * asked again.
	 */
	static Duration timeToLive(VatLookupStatus vatLookupStatus) {
		return switch (vatLookupStatus) {
			case REGISTERED -> Duration.ofHours(24);
			case NOT_REGISTERED -> Duration.ofHours(1);

			// Transient by definition. Kept longer, a failure that lasts
			// seconds at the registry would last minutes for the customer.

			case UNAVAILABLE -> Duration.ofMinutes(1);

			// Not a fact worth keeping, but a cooldown: a call that has just
			// failed is not made again straight away.

			case ERROR -> Duration.ofSeconds(10);
		};
	}

	/**
	 * Answers from the cache, from a call already in flight, or from a new
	 * call, in that order of preference.
	 *
	 * @param  vatId the normalized VAT number
	 * @param  loader makes the call to the registry. It runs on this thread,
	 *         only if this caller is the one that has to make the call, and it
	 *         is expected to return an answer rather than throw.
	 * @return the answer, or a refusal if a new call was needed and the
	 *         upstream budget had no slot for it
	 */
	public Result get(
		String vatId, Function<String, VatLookupResponse> loader) {

		CompletableFuture<TimestampedResponse> ownFuture =
			new CompletableFuture<>();

		// Written inside the remapping function and read after it. The map
		// runs the function on this thread, exactly once, before compute
		// returns, so nothing else can see it in between.

		long[] retryAfterSeconds = new long[1];

		CompletableFuture<TimestampedResponse> future = _futures.compute(
			vatId,
			(key, currentFuture) -> {
				if ((currentFuture != null) && !_isExpired(currentFuture)) {
					return currentFuture;
				}

				// A new call is needed. The budget is asked for in this same
				// atomic step. Reserving the entry first and asking after
				// would leave an empty future in the map whenever the budget
				// said no, and every caller already waiting on it would wait
				// forever. tryAcquire does no I/O, and the limiter never
				// touches this map, so holding both locks cannot deadlock.

				retryAfterSeconds[0] = _upstreamRateLimiter.tryAcquire();

				if (retryAfterSeconds[0] > 0) {

					// Nothing is installed. An expired entry goes too, which
					// changes nothing: expired already reads as absent.

					return null;
				}

				return ownFuture;
			});

		if (future == null) {
			return new Result(null, retryAfterSeconds[0]);
		}

		// The call itself happens here, outside compute. It can take seconds,
		// and inside compute it would block every other number that shares
		// this entry's bin for as long as it ran.

		if (future == ownFuture) {
			_call(vatId, loader, ownFuture);
		}

		TimestampedResponse timestampedResponse = future.join();

		return new Result(timestampedResponse.response(), 0);
	}

	/**
	 * What {@link #get} answers: a response, or a refusal carrying how long
	 * until the upstream budget has a slot again.
	 */
	public record Result(VatLookupResponse response, long retryAfterSeconds) {

		public boolean isRefused() {
			return response == null;
		}

	}

	private void _call(
		String vatId, Function<String, VatLookupResponse> loader,
		CompletableFuture<TimestampedResponse> future) {

		VatLookupResponse vatLookupResponse = null;

		try {
			vatLookupResponse = loader.apply(vatId);
		}
		finally {

			// A loader that throws, or returns nothing, would otherwise leave
			// this future incomplete, and everyone waiting on it waiting
			// forever. It completes anyway, as our own failure, and whatever
			// was thrown carries on to this caller.

			if (vatLookupResponse == null) {
				vatLookupResponse = VatLookupResponse.error("LOOKUP_FAILED");
			}

			future.complete(
				new TimestampedResponse(
					vatLookupResponse, _instantSource.instant()));
		}
	}

	private boolean _isExpired(CompletableFuture<TimestampedResponse> future) {

		// A call in flight never expires. Whoever asks now waits on it.

		if (!future.isDone()) {
			return false;
		}

		TimestampedResponse timestampedResponse = future.resultNow();

		VatLookupResponse vatLookupResponse = timestampedResponse.response();

		Instant expiresAt = timestampedResponse.computedAt(
		).plus(
			timeToLive(vatLookupResponse.getStatus())
		);

		return !_instantSource.instant(
		).isBefore(
			expiresAt
		);
	}

	private final Map<String, CompletableFuture<TimestampedResponse>> _futures =
		new ConcurrentHashMap<>();
	private final InstantSource _instantSource;
	private final UpstreamRateLimiter _upstreamRateLimiter;

	/**
	 * An answer and the moment it arrived. It exists only in this class and is
	 * never serialized, so the response itself stays unaware of how long it is
	 * trusted.
	 */
	private record TimestampedResponse(
		VatLookupResponse response, Instant computedAt) {
	}

}
