package com.forexbot.dto;

import java.util.List;

/**
 * Aggregated, LLM-ready market context for one symbol: recent OHLC arrays and
 * the latest tick, plus a preformatted prompt payload string.
 */
public record MarketContext(
        String symbol,
        String timeframe,
        int bars,
        List<Double> opens,
        List<Double> highs,
        List<Double> lows,
        List<Double> closes,
        Double lastClose,
        Double bid,
        Double ask,
        String promptPayload
) {
}

