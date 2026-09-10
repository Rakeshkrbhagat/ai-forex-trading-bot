package com.forexbot.service;

import com.forexbot.dto.MarketAnalysis;
import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.MarketTickRequest;
import com.forexbot.dto.TradeDecision;
import com.forexbot.dto.TradeDecisionSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Unified tick pipeline / execution dispatcher.
 * <p>
 * Orchestrates the full flow for a single market tick:
 * <ol>
 *   <li>Risk firewall validation (hardcoded limits + kill switch).</li>
 *   <li>Gemini market-structure evaluation.</li>
 *   <li>Binary TRENDING/SIDEWAYS filter.</li>
 *   <li>Execution dispatch: increments the thread-safe trade counter on
 *       confirmed trends and produces a structured {@link TradeDecision}.</li>
 * </ol>
 */
@Service
public class TickPipelineService {

    private static final Logger log = LoggerFactory.getLogger(TickPipelineService.class);

    private final BotStateManager stateManager;
    private final RiskFirewall riskFirewall;
    private final GeminiService geminiService;
    private final MarketStructureFilter marketStructureFilter;
    private final ExecutionService executionService;
    private final TradeSignalValidator signalValidator;
    private final ActivityFeedService activityFeed;
    private final AiDecisionEngine aiDecisionEngine;

    public TickPipelineService(BotStateManager stateManager,
                               RiskFirewall riskFirewall,
                               GeminiService geminiService,
                               MarketStructureFilter marketStructureFilter,
                               ExecutionService executionService,
                               TradeSignalValidator signalValidator,
                               ActivityFeedService activityFeed,
                               AiDecisionEngine aiDecisionEngine) {
        this.stateManager = stateManager;
        this.riskFirewall = riskFirewall;
        this.geminiService = geminiService;
        this.marketStructureFilter = marketStructureFilter;
        this.executionService = executionService;
        this.signalValidator = signalValidator;
        this.activityFeed = activityFeed;
        this.aiDecisionEngine = aiDecisionEngine;
    }

    /**
     * Runs the full pipeline for the supplied tick.
     *
     * @return the pipeline outcome (decision + whether it was blocked by risk).
     */
    public PipelineResult process(MarketTickRequest tick) {
        // 1) Risk validation first — non-negotiable.
        RiskFirewall.RiskCheckResult risk = riskFirewall.evaluate();
        if (!risk.allowed()) {
            log.warn("Tick for {} blocked by risk firewall: {}",
                    tick.currencyPair(), risk.reason());
            activityFeed.record(tick.currencyPair(), "REJECTED",
                    "Risk firewall blocked trade: " + risk.reason());
            return PipelineResult.riskBlocked(hold(tick,
                    "Risk firewall blocked trade: " + risk.reason()));
        }

        // 2) Gemini market-structure evaluation.
        String rawAnalysis = geminiService.analyzeMarketStructure(tick);
        MarketAnalysis analysis = marketStructureFilter.parse(rawAnalysis);

        // 3) Binary TRENDING/SIDEWAYS filter.
        MarketStructureFilter.FilterResult filter = marketStructureFilter.apply(analysis);
        if (!filter.proceed()) {
            activityFeed.record(tick.currencyPair(), "HOLD", filter.reason());
            return PipelineResult.completed(hold(tick, filter.reason()));
        }

        // 4) Execution dispatch — confirmed trend: increment thread-safe counter.
        int tradeNumber = stateManager.incrementTrades();
        TradeDecision.Action action = filter.action() == MarketAnalysis.Direction.BUY
                ? TradeDecision.Action.BUY
                : TradeDecision.Action.SELL;

        log.info("Executing trade #{} {} on {} ({})",
                tradeNumber, action, tick.currencyPair(), filter.reason());

        TradeDecision decision = new TradeDecision(
                tick.currencyPair(),
                action,
                tick.ask(),
                analysis.keySupport(),
                analysis.keyResistance(),
                filter.reason()
        );

        // 5) Guardrail validation + broker dispatch via the shared finalizer.
        return finalize(decision, tick.currencyPair(), tick.ask(), filter.reason());
    }

    /**
     * Consolidated candle-window pipeline used by the autonomous agent. Uses the
     * richer {@link AiDecisionEngine} (full OHLC context) instead of a single
     * tick, then funnels the decision through the SAME risk firewall, guardrail
     * validation and execution path as {@link #process(MarketTickRequest)}.
     */
    public PipelineResult processWindow(MarketDataWindow window) {
        String symbol = window != null ? window.symbol() : "unknown";

        // 1) Risk firewall first — non-negotiable.
        RiskFirewall.RiskCheckResult risk = riskFirewall.evaluate();
        if (!risk.allowed()) {
            log.warn("Window for {} blocked by risk firewall: {}", symbol, risk.reason());
            activityFeed.record(symbol, "REJECTED",
                    "Risk firewall blocked trade: " + risk.reason());
            return PipelineResult.riskBlocked(holdSymbol(symbol,
                    "Risk firewall blocked trade: " + risk.reason()));
        }

        // 2) LLM decision over the full candle window.
        TradeDecisionSignal signal = aiDecisionEngine.decide(window);
        if (!signal.isActionable()) {
            activityFeed.record(symbol, "HOLD", "AI decision: HOLD (conf " + signal.confidenceScore() + ")");
            return PipelineResult.completed(holdSymbol(symbol, "AI decision: HOLD"));
        }

        // 3) Map the validated signal into the shared TradeDecision structure.
        int tradeNumber = stateManager.incrementTrades();
        TradeDecision.Action action = "BUY".equalsIgnoreCase(signal.action())
                ? TradeDecision.Action.BUY
                : TradeDecision.Action.SELL;
        Double entry = window.lastClose();
        String rationale = "AI window decision (conf " + signal.confidenceScore() + ")";

        log.info("Executing trade #{} {} on {} ({})", tradeNumber, action, symbol, rationale);

        TradeDecision decision = new TradeDecision(
                symbol, action, entry, signal.sl(), signal.tp(), rationale);

        // 4) Guardrail validation + broker dispatch via the shared finalizer.
        return finalize(decision, symbol, entry, rationale);
    }

    /**
     * Shared tail: guardrail validation, risk-based sizing, broker dispatch and
     * activity-feed recording. Guarantees BOTH pipelines enforce the exact same
     * guardrails before any order reaches MT5.
     */
    private PipelineResult finalize(TradeDecision decision, String symbol,
                                    Double entryPrice, String rationale) {
        TradeSignalValidator.ValidationResult validation = signalValidator.validate(decision);
        if (!validation.approved()) {
            log.warn("{} on {} blocked by guardrails: {}",
                    decision.action(), symbol, validation.reason());
            activityFeed.record(symbol, "REJECTED",
                    "Guardrail validation blocked trade: " + validation.reason());
            return PipelineResult.completed(holdSymbol(symbol,
                    "Guardrail validation blocked trade: " + validation.reason()));
        }

        com.forexbot.dto.OrderResult execution =
                executionService.dispatch(decision, validation.volume());
        log.info("MT5 execution for {} {}: accepted={} status={} volume={}",
                decision.action(), symbol, execution.accepted(),
                execution.status(), validation.volume());
        activityFeed.record(symbol, decision.action().name(),
                String.format("%s %.2f lots @ %s -> %s (%s)", decision.action(),
                        validation.volume(), entryPrice, execution.status(), rationale));

        return PipelineResult.completed(decision);
    }

    private TradeDecision holdSymbol(String symbol, String rationale) {
        return new TradeDecision(symbol, TradeDecision.Action.HOLD,
                null, null, null, rationale);
    }

    private TradeDecision hold(MarketTickRequest tick, String rationale) {
        return new TradeDecision(
                tick.currencyPair(),
                TradeDecision.Action.HOLD,
                null,
                null,
                null,
                rationale
        );
    }

    /** Result of running the tick pipeline. */
    public record PipelineResult(TradeDecision decision, boolean riskBlocked) {
        public static PipelineResult completed(TradeDecision decision) {
            return new PipelineResult(decision, false);
        }

        public static PipelineResult riskBlocked(TradeDecision decision) {
            return new PipelineResult(decision, true);
        }
    }
}

