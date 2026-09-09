package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Strict, type-safe trading decision parsed from the LLM's structured JSON
 * output. The AI decision engine guarantees these fields via schema validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeDecisionSignal(
        String symbol,
        String action,
        Double volume,
        Double sl,
        Double tp,
        Double confidenceScore
) {

    /** Safe default returned on API timeout / malformed output. */
    public static TradeDecisionSignal hold(String symbol) {
        return new TradeDecisionSignal(symbol, "HOLD", 0.0, 0.0, 0.0, 0.0);
    }

    public boolean isActionable() {
        return "BUY".equalsIgnoreCase(action) || "SELL".equalsIgnoreCase(action);
    }
}

