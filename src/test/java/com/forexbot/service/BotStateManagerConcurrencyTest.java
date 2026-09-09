package com.forexbot.service;

import com.forexbot.dto.BotConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JTICKET-08 — Concurrency verification for the thread-safe {@link BotStateManager}.
 * <p>
 * Hammers the atomic counters, running P&L accumulator and the emergency kill
 * switch from many threads simultaneously to prove there are no lost updates or
 * race conditions in the bot's core mutable state.
 */
class BotStateManagerConcurrencyTest {

    private static final int THREADS = 64;
    private static final int ITERATIONS_PER_THREAD = 5_000;

    @Test
    void tradeCounterAndPnlAreCorrectUnderConcurrentLoad() throws InterruptedException {
        BotStateManager state = new BotStateManager();
        state.start("ACC-STRESS");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        state.incrementTrades();
                        state.addPnl(1.0);   // each iteration contributes +1.0
                        state.addPnl(-0.5);  // ...and -0.5 -> net +0.5 per iteration
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown(); // release all threads at once for maximum contention
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        int expectedTrades = THREADS * ITERATIONS_PER_THREAD;
        double expectedPnl = THREADS * ITERATIONS_PER_THREAD * 0.5;

        // No lost updates: totals must match exactly.
        assertThat(state.getTradesExecutedToday()).isEqualTo(expectedTrades);
        assertThat(state.getDailyPnlUsd()).isEqualTo(expectedPnl);
    }

    @Test
    void killSwitchIsEngagedExactlyOnceUnderConcurrentTrips() throws InterruptedException {
        BotStateManager state = new BotStateManager();
        state.start("ACC-KILL");
        assertThat(state.isRunning()).isTrue();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger firstTrips = new AtomicInteger(0);

        for (int t = 0; t < THREADS; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                    if (state.engageKillSwitch("trip-" + id)) {
                        firstTrips.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // compareAndSet guarantees only one thread wins the trip.
        assertThat(firstTrips.get()).isEqualTo(1);
        assertThat(state.isKillSwitchEngaged()).isTrue();
        // Kill switch must have forced the bot to stop.
        assertThat(state.isRunning()).isFalse();
        assertThat(state.getKillSwitchReason()).startsWith("trip-");
    }

    @Test
    void applyConfigPropagatesRiskLimits() {
        BotStateManager state = new BotStateManager();
        state.applyConfig(new BotConfig(
                "ACC-1", "EUR/USD", 25, 750.0, 1.0, 20));

        assertThat(state.getMaxDailyTrades()).isEqualTo(25);
        assertThat(state.getMaxDailyLossUsd()).isEqualTo(750.0);
        assertThat(state.getAccountId()).isEqualTo("ACC-1");
    }
}

