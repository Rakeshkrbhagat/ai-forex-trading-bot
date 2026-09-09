package com.forexbot.service;

import com.forexbot.dto.MarketTickRequest;
import com.forexbot.dto.RiskGuardrails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            marketDataService.fetchTick(symbol).ifPresentOrElse(
                    this::evaluate,
                    () -> log.debug("No market data for {}", symbol));
        }
    }

    private void evaluate(MarketTickRequest tick) {
        try {
            TickPipelineService.PipelineResult result = pipeline.process(tick);
            log.info("Autonomous cycle {} -> {} ({})",
                    tick.currencyPair(), result.decision().action(), result.decision().rationale());
        } catch (Exception ex) {
            log.error("Autonomous evaluation failed for {}: {}",
                    tick.currencyPair(), ex.getMessage());
        }
    }
}

