package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A window of recent OHLC market data for one symbol, used to build the LLM
 * decision-engine context.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketDataWindow(
        String symbol,
        String timeframe,
        int count,
        List<Candle> candles
) {

    /** Latest close price, or {@code null} when the window is empty. */
    public Double lastClose() {
        if (candles == null || candles.isEmpty()) {
            return null;
        }
        return candles.get(candles.size() - 1).close();
    }

    public boolean isEmpty() {
        return candles == null || candles.isEmpty();
    }
}

