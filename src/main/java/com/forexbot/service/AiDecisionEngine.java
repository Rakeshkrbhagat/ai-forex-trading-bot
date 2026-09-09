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

    private final GeminiService geminiService;
    private final SignalSchemaValidator schemaValidator;

    public AiDecisionEngine(GeminiService geminiService, SignalSchemaValidator schemaValidator) {
        this.geminiService = geminiService;
        this.schemaValidator = schemaValidator;
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
            String rawJson = geminiService.analyzeMarketData(window);
            TradeDecisionSignal signal = schemaValidator.parseAndValidate(rawJson);
            log.info("AI decision for {} -> {} (conf {})",
                    symbol, signal.action(), signal.confidenceScore());
            return signal;
        } catch (SignalSchemaValidator.InvalidSignalException ex) {
            log.warn("Malformed LLM output for {}; defaulting to HOLD: {}", symbol, ex.getMessage());
            return TradeDecisionSignal.hold(symbol);
        } catch (Exception ex) {
            // API timeout / connectivity / any other failure -> safe HOLD.
            log.warn("AI decision failed for {}; defaulting to HOLD: {}", symbol, ex.getMessage());
            return TradeDecisionSignal.hold(symbol);
        }
    }
}

