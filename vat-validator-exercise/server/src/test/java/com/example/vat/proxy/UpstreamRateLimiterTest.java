package com.example.vat.proxy;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Drives the limiter with a clock the test moves by hand, so every answer is
 * exact rather than approximately right.
 */
public class UpstreamRateLimiterTest {

	@Test
	public void testConcurrentCallersCannotSpendTheSameSlot() throws Exception {
		UpstreamRateLimiter upstreamRateLimiter = new UpstreamRateLimiter(
			UpstreamRateLimiter.LIMIT, UpstreamRateLimiter.WINDOW, () -> 0L);

		int callers = 64;

		CyclicBarrier cyclicBarrier = new CyclicBarrier(callers);
		ExecutorService executorService = Executors.newFixedThreadPool(callers);

		try {
			List<Future<Long>> futures = new ArrayList<>();

			for (int i = 0; i < callers; i++) {
				futures.add(
					executorService.submit(
						() -> {
							cyclicBarrier.await();

							return upstreamRateLimiter.tryAcquire();
						}));
			}

			int acquired = 0;

			for (Future<Long> future : futures) {
				if (future.get() == 0) {
					acquired++;
				}
			}

			Assertions.assertEquals(UpstreamRateLimiter.LIMIT, acquired);
		}
		finally {
			executorService.shutdownNow();
		}
	}

	@Test
	public void testRefusalSaysWhenTheOldestSlotFrees() {
		AtomicLong nanos = new AtomicLong();

		UpstreamRateLimiter upstreamRateLimiter = _upstreamRateLimiter(nanos);

		// Nine lookups, one a second, from 0s to 8s

		for (int i = 0; i < UpstreamRateLimiter.LIMIT; i++) {
			Assertions.assertEquals(0, upstreamRateLimiter.tryAcquire());

			nanos.addAndGet(_seconds(1));
		}

		// At 9s the one from 0s frees at 60s

		Assertions.assertEquals(51, upstreamRateLimiter.tryAcquire());

		// Part of a second left rounds up, never down to zero

		nanos.set(_seconds(59) + Duration.ofMillis(800).toNanos());

		Assertions.assertEquals(1, upstreamRateLimiter.tryAcquire());
	}

	@Test
	public void testWindowSlidesOneSlotAtATime() {
		AtomicLong nanos = new AtomicLong();

		UpstreamRateLimiter upstreamRateLimiter = _upstreamRateLimiter(nanos);

		// Nine lookups, ten seconds apart, from 0s to 80s

		for (int i = 0; i < UpstreamRateLimiter.LIMIT; i++) {
			Assertions.assertEquals(0, upstreamRateLimiter.tryAcquire());

			nanos.addAndGet(_seconds(10));
		}

		// At 90s the ones from 30s and earlier have left, five remain, so
		// four more fit and a fifth waits for the one from 40s

		for (int i = 0; i < 4; i++) {
			Assertions.assertEquals(0, upstreamRateLimiter.tryAcquire());
		}

		Assertions.assertEquals(10, upstreamRateLimiter.tryAcquire());

		// At 100s the one from 40s has left, one more fits, and the next
		// waits for the one from 50s

		nanos.set(_seconds(100));

		Assertions.assertEquals(0, upstreamRateLimiter.tryAcquire());
		Assertions.assertEquals(10, upstreamRateLimiter.tryAcquire());
	}

	private static long _seconds(long seconds) {
		return Duration.ofSeconds(
			seconds
		).toNanos();
	}

	private static UpstreamRateLimiter _upstreamRateLimiter(AtomicLong nanos) {
		return new UpstreamRateLimiter(
			UpstreamRateLimiter.LIMIT, UpstreamRateLimiter.WINDOW, nanos::get);
	}

}
