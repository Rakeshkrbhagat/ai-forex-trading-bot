package com.forexbot.service;

import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.RiskGuardrails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Autonomous, LLM-driven trading loop. On a fixed cadence it ingests live market
 * data for each allowed symbol and feeds it through the tick pipeline, where the
 * Gemini LLM evaluates setups and the guardrail validator authorizes execution.
 * <p>
 * The loop only runs when the bot is started AND the user has enabled autonomous
 * mode in their risk guardrails.
 */
@Service
public class AutonomousTradingAgent {

    private static final Logger log = LoggerFactory.getLogger(AutonomousTradingAgent.class);

    private final BotStateManager stateManager;
    private final RiskGuardrailStore guardrailStore;
    private final MarketDataService marketDataService;
    private final TickPipelineService pipeline;

    /** Timeframe + depth of the candle window fed to the LLM each cycle. */
    @Value("${agent.timeframe:M15}")
    private String timeframe;
    @Value("${agent.candles:100}")
    private int candles;

    public AutonomousTradingAgent(BotStateManager stateManager,
                                  RiskGuardrailStore guardrailStore,
                                  MarketDataService marketDataService,
                                  TickPipelineService pipeline) {
        this.stateManager = stateManager;
        this.guardrailStore = guardrailStore;
        this.marketDataService = marketDataService;
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelayString = "${agent.poll-interval-ms:15000}",
            initialDelayString = "${agent.initial-delay-ms:10000}")
    public void tickCycle() {
        RiskGuardrails guardrails = guardrailStore.get();
        if (!guardrails.autonomousEnabled() || !stateManager.isRunning()) {
            return;
        }
        if (guardrails.allowedSymbols() == null || guardrails.allowedSymbols().isEmpty()) {
            return;
        }

        for (String symbol : guardrails.allowedSymbols()) {
            marketDataService.fetchCandles(symbol, timeframe, candles).ifPresentOrElse(
                    this::evaluate,
                    () -> log.debug("No candle window for {}", symbol));
        }
    }

    private void evaluate(MarketDataWindow window) {
        try {
            TickPipelineService.PipelineResult result = pipeline.processWindow(window);
            log.info("Autonomous cycle {} -> {} ({})",
                    window.symbol(), result.decision().action(), result.decision().rationale());
        } catch (Exception ex) {
            log.error("Autonomous evaluation failed for {}: {}",
                    window.symbol(), ex.getMessage());
        }
    }
}

