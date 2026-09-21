package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

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

        /** Single API key for the selected provider (kept in memory only). */
        String apiKey,

        /**
         * Optional pool of API keys (1–5). When multiple free-tier keys are
         * supplied the backend uses the first and automatically fails over to
         * the next one when the active key is rate-limited (HTTP 429 / quota).
         */
        List<String> apiKeys,

        /** Candle timeframe fed to the model each cycle (e.g. M1, M5, M15, H1). */
        String timeframe,

        /** Trading style: INTRADAY, SCALPING or SWING. */
        String tradingStyle
) {

    /** Normalize {@code apiKeys} to a non-null, de-blanked, trimmed list. */
    public AiSettings {
        apiKeys = apiKeys == null ? List.of()
                : apiKeys.stream()
                        .filter(k -> k != null && !k.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    public static AiSettings defaults() {
        return new AiSettings("gemini", "gemini-2.0-flash", null, List.of(), "M15", "INTRADAY");
    }

    /**
     * All configured API keys in priority order. Prefers the multi-key pool;
     * falls back to the single {@link #apiKey} when the pool is empty.
     */
    public List<String> allApiKeys() {
        if (apiKeys != null && !apiKeys.isEmpty()) {
            return apiKeys;
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return List.of(apiKey.trim());
        }
        return List.of();
    }

    public boolean hasApiKey() {
        return !allApiKeys().isEmpty();
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

