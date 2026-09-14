package com.forexbot.service;

import com.forexbot.config.GeminiProperties;
import com.forexbot.dto.AiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the runtime AI + trading settings supplied from the
 * dashboard (provider, model, API key, timeframe, trading style).
 * <p>
 * Values configured here take precedence over the static
 * {@link GeminiProperties} / environment configuration, so the operator can
 * wire a model and API key at runtime without redeploying or setting Render
 * environment variables. The API key is held only in memory.
 */
@Service
public class AiSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsStore.class);

    private final GeminiProperties geminiProperties;
    private final AtomicReference<AiSettings> current = new AtomicReference<>();

    public AiSettingsStore(GeminiProperties geminiProperties) {
        this.geminiProperties = geminiProperties;
        // Seed from static config so behaviour is unchanged until the UI overrides it.
        this.current.set(new AiSettings(
                "gemini",
                geminiProperties.getModel(),
                geminiProperties.getApiKey(),
                "M15",
                "INTRADAY"));
    }

    public void save(AiSettings incoming) {
        AiSettings existing = current.get();
        // Preserve the previously-stored key when the UI submits a blank one
        // (so re-saving other fields does not wipe the key).
        String apiKey = (incoming.hasApiKey()) ? incoming.apiKey()
                : (existing != null ? existing.apiKey() : geminiProperties.getApiKey());
        AiSettings merged = new AiSettings(
                incoming.provider() != null ? incoming.provider() : "gemini",
                incoming.hasModel() ? incoming.model() : (existing != null ? existing.model() : geminiProperties.getModel()),
                apiKey,
                incoming.hasTimeframe() ? incoming.timeframe() : (existing != null ? existing.timeframe() : "M15"),
                incoming.hasTradingStyle() ? incoming.tradingStyle() : (existing != null ? existing.tradingStyle() : "INTRADAY"));
        current.set(merged);
        log.info("AI settings updated: provider={} model={} timeframe={} style={} apiKey={}",
                merged.provider(), merged.model(), merged.timeframe(), merged.tradingStyle(),
                merged.hasApiKey() ? "set" : "unset");
    }

    public AiSettings get() {
        return current.get();
    }

    /** Effective API key: UI-supplied override, else the static property. */
    public String effectiveApiKey() {
        AiSettings s = current.get();
        if (s != null && s.hasApiKey()) {
            return s.apiKey();
        }
        return geminiProperties.getApiKey();
    }

    /** Effective model id: UI-supplied override, else the static property. */
    public String effectiveModel() {
        AiSettings s = current.get();
        if (s != null && s.hasModel()) {
            return s.model();
        }
        return geminiProperties.getModel();
    }

    public String effectiveTimeframe() {
        AiSettings s = current.get();
        return (s != null && s.hasTimeframe()) ? s.timeframe() : null;
    }

    public String effectiveTradingStyle() {
        AiSettings s = current.get();
        return (s != null && s.hasTradingStyle()) ? s.tradingStyle() : "INTRADAY";
    }

    /**
     * Duration of one candle for the selected timeframe, in milliseconds. Used
     * to pace AI calls so a new request fires roughly once per candle close
     * (M1 → 1min, M5 → 5min, M15 → 15min, H1 → 1hr, H4 → 4hr, D1 → 1day).
     * Returns 0 when no timeframe is set (caller applies its own default).
     */
    public long timeframeIntervalMs() {
        String tf = effectiveTimeframe();
        if (tf == null || tf.isBlank()) {
            return 0L;
        }
        return switch (tf.trim().toUpperCase()) {
            case "M1" -> 60_000L;
            case "M5" -> 5 * 60_000L;
            case "M15" -> 15 * 60_000L;
            case "M30" -> 30 * 60_000L;
            case "H1" -> 60 * 60_000L;
            case "H4" -> 4 * 60 * 60_000L;
            case "D1" -> 24 * 60 * 60_000L;
            default -> 0L;
        };
    }

    public boolean hasApiKey() {
        String key = effectiveApiKey();
        return key != null && !key.isBlank();
    }
}

