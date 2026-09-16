package com.example.vat.proxy;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Drives the cache with clocks the test moves by hand, and with calls that
 * finish only when the test says so, so that nothing here depends on timing.
 */
public class VatLookupCacheTest {

	/**
	 * A call in flight is shared by everyone who asks while it runs.
	 *
	 * <p>
	 * The first caller is let in alone, so that the test knows who makes the
	 * call and can hold it open. The race for who gets to make it is
	 * <code>VatLookupServletTest</code>'s.
	 * </p>
	 */
	@Test
	public void testCallersDuringACallShareIt() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch answerLatch = new CountDownLatch(1);
		CountDownLatch callLatch = new CountDownLatch(1);
		VatLookupResponse vatLookupResponse = _registered();

		Function<String, VatLookupResponse> loader = vatId -> {
			calls.incrementAndGet();

			callLatch.countDown();

			_await(answerLatch);

			return vatLookupResponse;
		};

		// One slot only, so that a caller that started a call of its own
		// instead of sharing would be refused, and it would show

		VatLookupCache vatLookupCache = new VatLookupCache(
			new UpstreamRateLimiter(1, UpstreamRateLimiter.WINDOW, () -> 0L),
			InstantSource.fixed(_START));

		VatLookupCache.Result[] results = new VatLookupCache.Result[8];

		Thread callerThread = Thread.ofPlatform(
		).start(
			() -> results[0] = vatLookupCache.get(_VAT_ID, loader)
		);

		callLatch.await();

		List<Thread> waiterThreads = new ArrayList<>();

		for (int i = 1; i < results.length; i++) {
			int index = i;

			waiterThreads.add(
				Thread.ofPlatform(
				).start(
					() -> results[index] = vatLookupCache.get(_VAT_ID, loader)
				));
		}

		_awaitParked(waiterThreads);

		Assertions.assertEquals(1, calls.get());

		answerLatch.countDown();

		_join(callerThread);

		for (Thread waiterThread : waiterThreads) {
			_join(waiterThread);
		}

		for (VatLookupCache.Result result : results) {
			Assertions.assertFalse(result.isRefused(), "Refused: " + result);
			Assertions.assertSame(vatLookupResponse, result.response());
		}

		Assertions.assertEquals(1, calls.get());
	}

	/**
	 * Each outcome is served for exactly its own time to live, and then asked
	 * for again.
	 */
	@Test
	public void testEachOutcomeIsKeptForItsOwnTime() {
		Map<VatLookupResponse, Duration> timesToLive = Map.of(
			_registered(), Duration.ofHours(24),
			VatLookupResponse.notRegistered(), Duration.ofHours(1),
			VatLookupResponse.unavailable("MEMBER_STATE_UNAVAILABLE"),
			Duration.ofMinutes(1),
			VatLookupResponse.error("UPSTREAM_TIMEOUT"), Duration.ofSeconds(10));

		timesToLive.forEach(
			(vatLookupResponse, timeToLive) -> {
				AtomicInteger calls = new AtomicInteger();
				AtomicReference<Instant> now = new AtomicReference<>(_START);

				Function<String, VatLookupResponse> loader = vatId -> {
					calls.incrementAndGet();

					return vatLookupResponse;
				};

				VatLookupCache vatLookupCache = new VatLookupCache(
					new UpstreamRateLimiter(), now::get);

				Assertions.assertSame(
					vatLookupResponse,
					vatLookupCache.get(
						_VAT_ID, loader
					).response());

				// Served as it is up to the last instant of its time to live

				now.set(
					_START.plus(
						timeToLive
					).minusNanos(
						1
					));

				Assertions.assertSame(
					vatLookupResponse,
					vatLookupCache.get(
						_VAT_ID, loader
					).response());
				Assertions.assertEquals(
					1, calls.get(),
					"Called again before its time: " + vatLookupResponse);

				// And not a moment longer

				now.set(_START.plus(timeToLive));

				vatLookupCache.get(_VAT_ID, loader);

				Assertions.assertEquals(
					2, calls.get(),
					"Not called again after its time: " + vatLookupResponse);
			});
	}

	/**
	 * A call that fails in a way the loader did not turn into an answer still
	 * answers everyone waiting on it, and the one who made it still hears what
	 * went wrong.
	 */
	@Test
	public void testFailedCallStillAnswersEveryoneWaiting() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch callLatch = new CountDownLatch(1);
		CountDownLatch failLatch = new CountDownLatch(1);
		AtomicReference<Throwable> thrown = new AtomicReference<>();

		VatLookupCache vatLookupCache = new VatLookupCache(
			new UpstreamRateLimiter(), InstantSource.fixed(_START));

		Thread callerThread = Thread.ofPlatform(
		).start(
			() -> {
				try {
					vatLookupCache.get(
						_VAT_ID,
						vatId -> {
							calls.incrementAndGet();

							callLatch.countDown();

							_await(failLatch);

							throw new IllegalStateException("Broken loader");
						});
				}
				catch (IllegalStateException illegalStateException) {
					thrown.set(illegalStateException);
				}
			}
		);

		callLatch.await();

		VatLookupCache.Result[] results = new VatLookupCache.Result[1];

		Thread waiterThread = Thread.ofPlatform(
		).start(
			() -> results[0] = vatLookupCache.get(
				_VAT_ID,
				vatId -> {
					calls.incrementAndGet();

					return _registered();
				})
		);

		_awaitParked(List.of(waiterThread));

		failLatch.countDown();

		_join(callerThread);
		_join(waiterThread);

		Assertions.assertInstanceOf(IllegalStateException.class, thrown.get());
		Assertions.assertEquals(1, calls.get());

		VatLookupResponse vatLookupResponse = results[0].response();

		Assertions.assertEquals(
			VatLookupStatus.ERROR, vatLookupResponse.getStatus());
		Assertions.assertEquals(
			"LOOKUP_FAILED", vatLookupResponse.getReason());
	}

	/**
	 * A refusal installs nothing. Had it left an empty future behind, the next
	 * caller for that number would wait on it forever.
	 */
	@Test
	public void testRefusalLeavesNothingToWaitOn() {
		AtomicInteger calls = new AtomicInteger();
		AtomicLong nanos = new AtomicLong();
		AtomicReference<Instant> now = new AtomicReference<>(_START);

		Function<String, VatLookupResponse> loader = vatId -> {
			calls.incrementAndGet();

			return _registered();
		};

		VatLookupCache vatLookupCache = new VatLookupCache(
			new UpstreamRateLimiter(1, UpstreamRateLimiter.WINDOW, nanos::get),
			now::get);

		// The only slot goes to one number

		Assertions.assertFalse(
			vatLookupCache.get(
				_VAT_ID, loader
			).isRefused());

		// A number never asked for is refused

		VatLookupCache.Result result = vatLookupCache.get(
			_OTHER_VAT_ID, loader);

		Assertions.assertTrue(result.isRefused());
		Assertions.assertEquals(60, result.retryAfterSeconds());

		// So is one whose answer has expired: stale is not served

		now.set(_START.plus(Duration.ofDays(1)));

		Assertions.assertTrue(
			vatLookupCache.get(
				_VAT_ID, loader
			).isRefused());

		Assertions.assertEquals(1, calls.get());

		// Once the slot frees, the refused number is looked up

		nanos.set(UpstreamRateLimiter.WINDOW.toNanos());

		VatLookupCache.Result laterResult = Assertions.assertTimeoutPreemptively(
			Duration.ofSeconds(5),
			() -> vatLookupCache.get(_OTHER_VAT_ID, loader),
			"Waited on a call nobody was making");

		Assertions.assertFalse(laterResult.isRefused());
		Assertions.assertEquals(2, calls.get());
	}

	private static void _await(CountDownLatch countDownLatch) {
		try {
			countDownLatch.await();
		}
		catch (InterruptedException interruptedException) {
			throw new IllegalStateException(interruptedException);
		}
	}

	/**
	 * Polls until every thread has parked or finished. Inside
	 * {@link VatLookupCache#get}, with a call already in flight, the only place
	 * a caller can park is waiting on that call.
	 */
	private static void _awaitParked(List<Thread> threads) throws Exception {
		long deadline = System.nanoTime() + _TIMEOUT.toNanos();

		while (!threads.stream(
				).allMatch(
					thread -> {
						Thread.State state = thread.getState();

						return (state == Thread.State.WAITING) ||
							   (state == Thread.State.TERMINATED);
					}
				)) {

			Assertions.assertTrue(
				System.nanoTime() < deadline,
				"Callers never settled: " + threads);

			Thread.sleep(1);
		}
	}

	private static void _join(Thread thread) throws Exception {
		Assertions.assertTrue(
			thread.join(_TIMEOUT), "Still running after " + _TIMEOUT);
	}

	private static VatLookupResponse _registered() {
		return VatLookupResponse.registered(
			"Registered Trader ES", "1 Example Street, Springfield");
	}

	private static final String _OTHER_VAT_ID = "FR40303265045";

	private static final Instant _START = Instant.parse("2026-01-01T00:00:00Z");

	private static final Duration _TIMEOUT = Duration.ofSeconds(10);

	private static final String _VAT_ID = "ESB12345678";

}
