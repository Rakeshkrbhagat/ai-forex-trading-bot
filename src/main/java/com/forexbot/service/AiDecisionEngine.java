package com.forexbot.service;

import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.TradeDecisionSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Core autonomous AI decision engine. Packages live MT5 price action into a
 * structured prompt, queries the LLM as an autonomous trading agent, and parses
 * the response into a strict, type-safe {@link TradeDecisionSignal}. Any API
 * timeout or malformed response falls back safely to {@code HOLD}.
 */
@Service
public class AiDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(AiDecisionEngine.class);

    private final LlmRouterService llmRouter;
    private final SignalSchemaValidator schemaValidator;
    private final ActivityFeedService activityFeed;

    public AiDecisionEngine(LlmRouterService llmRouter, SignalSchemaValidator schemaValidator,
                            ActivityFeedService activityFeed) {
        this.llmRouter = llmRouter;
        this.schemaValidator = schemaValidator;
        this.activityFeed = activityFeed;
    }

    /**
     * Produces a validated trade decision for a candle window.
     *
     * @return a type-safe {@link TradeDecisionSignal}; never {@code null}
     *         (defaults to HOLD on failure).
     */
    public TradeDecisionSignal decide(MarketDataWindow window) {
        String symbol = window != null ? window.symbol() : "unknown";
        if (window == null || window.isEmpty()) {
            log.warn("No market data for {}; defaulting to HOLD", symbol);
            return TradeDecisionSignal.hold(symbol);
        }

        try {
            String rawJson = llmRouter.analyzeMarketData(window);
            log.info("Raw LLM response for {}: {}", symbol, oneLine(rawJson));
            TradeDecisionSignal signal = schemaValidator.parseAndValidate(rawJson);
            log.info("AI decision for {} -> {} (conf {})",
                    symbol, signal.action(), signal.confidenceScore());
            return signal;
        } catch (SignalSchemaValidator.InvalidSignalException ex) {
            String reason = "LLM output was malformed/invalid: " + ex.getMessage();
            log.warn("Malformed LLM output for {}; defaulting to HOLD: {}", symbol, ex.getMessage());
            activityFeed.record(symbol, "REJECTED", reason);
            return TradeDecisionSignal.hold(symbol);
        } catch (LlmRouterService.RateLimitedException ex) {
            // Provider quota / rate-limit: back off quietly instead of spamming.
            log.warn("AI rate-limited for {}; holding: {}", symbol, ex.getMessage());
            activityFeed.record(symbol, "HOLD", "AI paused (rate limit): " + ex.getMessage());
            return TradeDecisionSignal.hold(symbol);
        } catch (Exception ex) {
            // API timeout / connectivity / missing key / any other failure -> safe HOLD.
            String reason = "LLM call failed: " + ex.getMessage()
                    + " (check the AI provider, model name, API key & network).";
            log.warn("AI decision failed for {}; defaulting to HOLD: {}", symbol, ex.getMessage());
            activityFeed.record(symbol, "REJECTED", reason);
            return TradeDecisionSignal.hold(symbol);
        }
    }

    /** Collapse a raw model response to a single, log-friendly line. */
    private static String oneLine(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}

