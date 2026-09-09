package com.forexbot.service;

import com.forexbot.dto.MarketAnalysis;
import com.forexbot.dto.MarketTickRequest;
import com.forexbot.dto.TradeDecision;
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

    public TickPipelineService(BotStateManager stateManager,
                               RiskFirewall riskFirewall,
                               GeminiService geminiService,
                               MarketStructureFilter marketStructureFilter,
                               ExecutionService executionService,
                               TradeSignalValidator signalValidator) {
        this.stateManager = stateManager;
        this.riskFirewall = riskFirewall;
        this.geminiService = geminiService;
        this.marketStructureFilter = marketStructureFilter;
        this.executionService = executionService;
        this.signalValidator = signalValidator;
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
            return PipelineResult.riskBlocked(hold(tick,
                    "Risk firewall blocked trade: " + risk.reason()));
        }

        // 2) Gemini market-structure evaluation.
        String rawAnalysis = geminiService.analyzeMarketStructure(tick);
        MarketAnalysis analysis = marketStructureFilter.parse(rawAnalysis);

        // 3) Binary TRENDING/SIDEWAYS filter.
        MarketStructureFilter.FilterResult filter = marketStructureFilter.apply(analysis);
        if (!filter.proceed()) {
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

        // 5) Guardrail validation of the LLM signal + risk-based position sizing.
        TradeSignalValidator.ValidationResult validation = signalValidator.validate(decision);
        if (!validation.approved()) {
            log.warn("Trade #{} {} on {} blocked by guardrails: {}",
                    tradeNumber, action, tick.currencyPair(), validation.reason());
            return PipelineResult.completed(hold(tick,
                    "Guardrail validation blocked trade: " + validation.reason()));
        }

        // 6) Broker dispatch: Gemini signal -> risk firewall -> guardrails -> MT5 order.
        com.forexbot.dto.OrderResult execution =
                executionService.dispatch(decision, validation.volume());
        log.info("MT5 execution for trade #{} {} {}: accepted={} status={} volume={}",
                tradeNumber, action, tick.currencyPair(),
                execution.accepted(), execution.status(), validation.volume());

        return PipelineResult.completed(decision);
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

