package com.example.vat.proxy;

import java.time.Duration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Rations our calls to the registry, whose quota is shared by every customer
 * on the site at once. There is one instance per process, and it does not know
 * or care who is asking.
 *
 * <p>
 * It is a sliding window kept as a log of the moments we spent a lookup.
 * Unlike a per-minute counter, the log can say exactly when the next slot
 * frees up: when the oldest entry leaves the window.
 * </p>
 *
 * <p>
 * It never queues. A caller either gets a slot now or learns how long to wait.
 * </p>
 */
public class UpstreamRateLimiter {

	/**
	 * Lookups we allow ourselves per {@link #WINDOW}. That is one fewer than
	 * the registry's ten, as margin for what this process cannot see: we count
	 * a lookup when we send it, and the registry counts it when it arrives, by
	 * calendar minute.
	 */
	public static final int LIMIT = 9;

	public static final Duration WINDOW = Duration.ofMinutes(1);

	public UpstreamRateLimiter() {
		this(LIMIT, WINDOW, System::nanoTime);
	}

	/**
	 * @param nanoClock a monotonic clock in nanoseconds, like
	 *        {@link System#nanoTime()}, so that a wall clock adjustment cannot
	 *        empty or freeze the window
	 */
	UpstreamRateLimiter(int limit, Duration window, LongSupplier nanoClock) {
		if (limit < 1) {
			throw new IllegalArgumentException("Limit must be at least 1");
		}

		_limit = limit;
		_windowNanos = window.toNanos();
		_nanoClock = nanoClock;
	}

	/**
	 * Takes a slot if there is one.
	 *
	 * <p>
	 * Checking and taking happen under one lock. Otherwise, two callers could
	 * both see the last free slot before either of them took it, and both
	 * would spend it.
	 * </p>
	 *
	 * @return <code>0</code> if a slot was taken. Otherwise, the whole seconds
	 *         until one frees up, rounded up and never less than
	 *         <code>1</code>, and no slot was taken.
	 */
	public synchronized long tryAcquire() {
		long now = _nanoClock.getAsLong();

		while (!_timestamps.isEmpty() &&
			   ((now - _timestamps.peekFirst()) >= _windowNanos)) {

			_timestamps.pollFirst();
		}

		if (_timestamps.size() < _limit) {
			_timestamps.addLast(now);

			return 0;
		}

		// The oldest entry is still inside the window, or it would have been
		// evicted above, so the wait is strictly positive. Rounding up means
		// a caller that waits exactly this long finds the slot free.

		long waitNanos = (_timestamps.peekFirst() + _windowNanos) - now;

		return Math.ceilDiv(waitNanos, _NANOS_PER_SECOND);
	}

	private static final long _NANOS_PER_SECOND = Duration.ofSeconds(
		1
	).toNanos();

	private final int _limit;
	private final LongSupplier _nanoClock;

	/**
	 * Oldest first. It never holds more than {@link #_limit} entries, because
	 * a refused call leaves nothing behind.
	 */
	private final Deque<Long> _timestamps = new ArrayDeque<>();

	private final long _windowNanos;

}
