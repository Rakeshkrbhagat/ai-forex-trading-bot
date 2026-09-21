package com.forexbot.service;

import com.forexbot.config.GeminiProperties;
import com.forexbot.dto.AiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Index of the API key currently in use within the configured key pool. */
    private final AtomicInteger activeIndex = new AtomicInteger(0);

    /** Keys tried in the current rate-limit failure streak (reset on success). */
    private final AtomicInteger rotationAttempts = new AtomicInteger(0);

    public AiSettingsStore(GeminiProperties geminiProperties) {
        this.geminiProperties = geminiProperties;
        // Seed from static config so behaviour is unchanged until the UI overrides it.
        this.current.set(new AiSettings(
                "gemini",
                geminiProperties.getModel(),
                geminiProperties.getApiKey(),
                List.of(),
                "M15",
                "INTRADAY"));
    }

    public void save(AiSettings incoming) {
        AiSettings existing = current.get();
        // Preserve the previously-stored key pool when the UI submits none
        // (so re-saving other fields does not wipe the keys).
        List<String> keys = !incoming.allApiKeys().isEmpty()
                ? incoming.allApiKeys()
                : (existing != null ? existing.allApiKeys() : List.of());
        AiSettings merged = new AiSettings(
                incoming.provider() != null ? incoming.provider() : "gemini",
                incoming.hasModel() ? incoming.model() : (existing != null ? existing.model() : geminiProperties.getModel()),
                null,
                keys,
                incoming.hasTimeframe() ? incoming.timeframe() : (existing != null ? existing.timeframe() : "M15"),
                incoming.hasTradingStyle() ? incoming.tradingStyle() : (existing != null ? existing.tradingStyle() : "INTRADAY"));
        current.set(merged);
        // Restart from the first key whenever settings change.
        activeIndex.set(0);
        rotationAttempts.set(0);
        log.info("AI settings updated: provider={} model={} timeframe={} style={} apiKeys={}",
                merged.provider(), merged.model(), merged.timeframe(), merged.tradingStyle(),
                merged.allApiKeys().isEmpty() ? "unset" : merged.allApiKeys().size() + " configured");
    }

    public AiSettings get() {
        return current.get();
    }

    /** Effective API key: the currently-active key from the UI pool, else the static property. */
    public String effectiveApiKey() {
        List<String> keys = configuredKeys();
        if (!keys.isEmpty()) {
            return keys.get(activeIndex.get() % keys.size());
        }
        return geminiProperties.getApiKey();
    }

    /** Configured API key pool from the runtime settings (may be empty). */
    private List<String> configuredKeys() {
        AiSettings s = current.get();
        return s != null ? s.allApiKeys() : List.of();
    }

    /** Number of API keys currently configured in the pool. */
    public int apiKeyCount() {
        return configuredKeys().size();
    }

    /** 0-based index of the API key currently in use. */
    public int activeApiKeyIndex() {
        int count = apiKeyCount();
        return count == 0 ? 0 : activeIndex.get() % count;
    }

    /**
     * Advance to the next API key in the pool after a rate-limit (429). Returns
     * {@code true} when a fresh, not-yet-tried key became active this failure
     * streak; {@code false} when only one key is configured or every key has
     * already been tried (the caller should then fall back to a cooldown).
     */
    public synchronized boolean rotateApiKey() {
        int count = apiKeyCount();
        if (count <= 1) {
            return false;
        }
        if (rotationAttempts.incrementAndGet() >= count) {
            // Every key tried this streak — give up so the caller cools down.
            rotationAttempts.set(0);
            return false;
        }
        activeIndex.set((activeIndex.get() + 1) % count);
        log.warn("AI API key rate-limited; failing over to key #{} of {}.",
                activeIndex.get() + 1, count);
        return true;
    }

    /** Reset the rotation streak after a successful call. */
    public void resetRotation() {
        rotationAttempts.set(0);
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

