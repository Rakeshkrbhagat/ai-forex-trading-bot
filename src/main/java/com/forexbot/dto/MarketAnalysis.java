package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parsed market-structure analysis returned by the Gemini model.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketAnalysis(
        String currencyPair,
        Structure structure,
        Direction direction,
        Double keySupport,
        Double keyResistance,
        Double confidence,
        String rationale
) {
    /** Binary market-structure classification used by the filter. */
    public enum Structure {
        TRENDING,
        SIDEWAYS
    }

    /** Suggested trade direction from the model. */
    public enum Direction {
        BUY,
        SELL,
        HOLD
    }
}

