package com.forexbot.service;

import org.springframework.stereotype.Service;

/**
 * Non-negotiable, hardcoded risk firewall executed on every market tick before
 * any trade may be processed. All checks read/write thread-safe atomic state so
 * they remain correct under concurrent tick requests.
 */
@Service
public class RiskFirewall {

    private final BotStateManager state;

    public RiskFirewall(BotStateManager state) {
        this.state = state;
    }

    /**
     * Evaluates the hardcoded risk rules against the current atomic state.
     * <p>
     * Rules (in order):
     * <ol>
     *   <li>If {@code tradesToday >= maxDailyTrades} the bot locks its running
     *       state and halts execution.</li>
     *   <li>If {@code pnlToday <= -abs(maxDailyLossUsd)} the emergency kill
     *       switch triggers immediately.</li>
     * </ol>
     *
     * @return a {@link RiskCheckResult} describing whether trading is permitted.
     */
    public RiskCheckResult evaluate() {
        // If the kill switch is already engaged, nothing else may pass.
        if (state.isKillSwitchEngaged()) {
            return RiskCheckResult.block(state.getKillSwitchReason());
        }

        // Rule 1: Daily trade cap -> lock running state and halt.
        int tradesToday = state.getTradesExecutedToday();
        int maxDailyTrades = state.getMaxDailyTrades();
        if (tradesToday >= maxDailyTrades) {
            String reason = "Max daily trades reached: " + tradesToday + "/" + maxDailyTrades;
            state.engageKillSwitch(reason);
            return RiskCheckResult.block(reason);
        }

        // Rule 2: Daily loss limit -> emergency kill switch.
        double pnlToday = state.getDailyPnlUsd();
        double lossLimit = -Math.abs(state.getMaxDailyLossUsd());
        if (pnlToday <= lossLimit) {
            String reason = "Max daily loss breached: pnl=" + pnlToday + " limit=" + lossLimit;
            state.engageKillSwitch(reason);
            return RiskCheckResult.block(reason);
        }

        // The bot must be running to trade.
        if (!state.isRunning()) {
            return RiskCheckResult.block("Bot is not running");
        }

        return RiskCheckResult.permit();
    }

    /** Outcome of a firewall evaluation. */
    public record RiskCheckResult(boolean allowed, String reason) {
        public static RiskCheckResult permit() {
            return new RiskCheckResult(true, null);
        }

        public static RiskCheckResult block(String reason) {
            return new RiskCheckResult(false, reason);
        }
    }
}

