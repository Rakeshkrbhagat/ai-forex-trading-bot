package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Runtime, dashboard-configurable AI + trading settings. These override the
 * static environment/application.properties values so the operator can pick the
 * model, supply an API key, and choose the timeframe / trading style directly
 * from the UI — no Render environment variables required.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSettings(

        /** LLM provider identifier (currently "gemini"). */
        String provider,

        /** Model id used for generateContent calls (e.g. "gemini-2.0-flash"). */
        String model,

        /** API key for the selected provider (kept in memory only). */
        String apiKey,

        /** Candle timeframe fed to the model each cycle (e.g. M1, M5, M15, H1). */
        String timeframe,

        /** Trading style: INTRADAY, SCALPING or SWING. */
        String tradingStyle
) {

    public static AiSettings defaults() {
        return new AiSettings("gemini", "gemini-2.0-flash", null, "M15", "INTRADAY");
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }

    public boolean hasTimeframe() {
        return timeframe != null && !timeframe.isBlank();
    }

    public boolean hasTradingStyle() {
        return tradingStyle != null && !tradingStyle.isBlank();
    }
}

