package com.forexbot.service;

import com.forexbot.dto.BotConfig;
import com.google.common.util.concurrent.AtomicDouble;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the trading bot's runtime lifecycle state.
 * Uses atomic primitives so lifecycle endpoints can be invoked concurrently
 * without corrupting the running flag, trade counters or running P&L.
 */
@Component
public class BotStateManager {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger tradesExecutedToday = new AtomicInteger(0);
    private final AtomicDouble dailyPnlUsd = new AtomicDouble(0.0);
    private final AtomicReference<String> accountId = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastUpdated = new AtomicReference<>(Instant.now());

    // --- Risk firewall limits & kill switch (thread-safe) ---
    private final AtomicInteger maxDailyTrades = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicDouble maxDailyLossUsd = new AtomicDouble(Double.MAX_VALUE);
    private final AtomicBoolean killSwitchEngaged = new AtomicBoolean(false);
    private final AtomicReference<String> killSwitchReason = new AtomicReference<>(null);

    /**
     * Applies the risk-management limits from the supplied configuration.
     */
    public void applyConfig(BotConfig config) {
        this.accountId.set(config.accountId());
        this.maxDailyTrades.set(config.maxDailyTrades());
        this.maxDailyLossUsd.set(Math.abs(config.maxDailyLossUsd()));
        touch();
    }

    public int getMaxDailyTrades() {
        return maxDailyTrades.get();
    }

    public double getMaxDailyLossUsd() {
        return maxDailyLossUsd.get();
    }

    /**
     * Engages the emergency kill switch: forces the running flag to false and
     * records the reason. Idempotent under concurrent invocation.
     *
     * @return {@code true} if this call engaged the kill switch, {@code false}
     * if it was already engaged.
     */
    public boolean engageKillSwitch(String reason) {
        boolean firstTrip = killSwitchEngaged.compareAndSet(false, true);
        if (firstTrip) {
            killSwitchReason.set(reason);
        }
        running.set(false);
        touch();
        return firstTrip;
    }

    public boolean isKillSwitchEngaged() {
        return killSwitchEngaged.get();
    }

    public String getKillSwitchReason() {
        return killSwitchReason.get();
    }

    /**
     * Starts the bot: sets the running flag to true and resets the daily counters.
     *
     * @return {@code true} if the bot transitioned from stopped to running,
     * {@code false} if it was already running.
     */
    public boolean start(String accountId) {
        this.accountId.set(accountId);
        resetDailyCounters();
        killSwitchEngaged.set(false);
        killSwitchReason.set(null);
        boolean transitioned = running.compareAndSet(false, true);
        touch();
        return transitioned;
    }

    /**
     * Stops the bot: toggles the running flag to false.
     *
     * @return {@code true} if the bot transitioned from running to stopped,
     * {@code false} if it was already stopped.
     */
    public boolean stop() {
        boolean transitioned = running.compareAndSet(true, false);
        touch();
        return transitioned;
    }

    /** Resets the daily trade count and running P&L to zero. */
    public void resetDailyCounters() {
        tradesExecutedToday.set(0);
        dailyPnlUsd.set(0.0);
        touch();
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getTradesExecutedToday() {
        return tradesExecutedToday.get();
    }

    public int incrementTrades() {
        touch();
        return tradesExecutedToday.incrementAndGet();
    }

    public double getDailyPnlUsd() {
        return dailyPnlUsd.get();
    }

    public double addPnl(double delta) {
        touch();
        return dailyPnlUsd.addAndGet(delta);
    }

    public String getAccountId() {
        return accountId.get();
    }

    public Instant getLastUpdated() {
        return lastUpdated.get();
    }

    private void touch() {
        lastUpdated.set(Instant.now());
    }
}

