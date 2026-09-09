package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single OHLC candle streamed from the local MT5 bridge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Candle(
        String time,
        double open,
        double high,
        double low,
        double close,
        long tickVolume
) {
}

