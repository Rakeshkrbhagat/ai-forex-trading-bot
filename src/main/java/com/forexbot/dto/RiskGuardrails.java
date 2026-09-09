package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * User-defined risk guardrails that constrain the autonomous LLM agent.
 * Rather than issuing manual buy/sell actions, the user configures the
 * boundaries within which the AI is permitted to trade.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskGuardrails(

        /** Max capital risked per trade, as a percentage of balance (e.g. 1.0). */
        double maxRiskPercent,

        /** Symbols the agent may trade (e.g. ["EURUSD", "XAUUSD"]). */
        List<String> allowedSymbols,

        /** Absolute max drawdown in USD before the agent halts. */
        double maxDrawdownUsd,

        /** Hard stop-loss distance in pips. */
        int stopLossPips,

        /** Take-profit distance in pips. */
        int takeProfitPips,

        /** Reference account balance in USD used for position sizing. */
        double accountBalanceUsd,

        /** Master switch enabling the autonomous trading loop. */
        boolean autonomousEnabled
) {

    /** Sensible defaults used until the user configures guardrails. */
    public static RiskGuardrails defaults() {
        return new RiskGuardrails(
                1.0,
                List.of("EURUSD", "XAUUSD"),
                500.0,
                20,
                40,
                10_000.0,
                false
        );
    }

    public boolean permits(String symbol) {
        if (allowedSymbols == null || allowedSymbols.isEmpty()) {
            return false;
        }
        return allowedSymbols.stream().anyMatch(s -> s.equalsIgnoreCase(symbol));
    }
}

