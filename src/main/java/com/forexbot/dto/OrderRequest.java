package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Order payload dispatched from the Spring Boot backend to the Python MT5
 * bridge. Prices are optional; the bridge computes SL/TP from pip distances
 * when explicit prices are not supplied.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderRequest(
        String symbol,
        String side,
        double volume,
        int stopLossPips,
        int takeProfitPips,
        Double entryPrice,
        @com.fasterxml.jackson.annotation.JsonProperty("sl") Double stopLossPrice,
        @com.fasterxml.jackson.annotation.JsonProperty("tp") Double takeProfitPrice,
        int maxSlippagePoints,
        long magicNumber,
        String comment,
        Mt5Credentials credentials,
        boolean breakEven,
        double beTriggerPct,
        boolean trailSwing,
        String timeframe
) {
}

