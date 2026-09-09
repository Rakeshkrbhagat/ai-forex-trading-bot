package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Outbound decision produced by the trading engine for a given market tick.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeDecision(
        String currencyPair,
        Action action,
        Double entryPrice,
        Double stopLossPrice,
        Double takeProfitPrice,
        String rationale
) {
    public enum Action {
        BUY,
        SELL,
        HOLD
    }
}

