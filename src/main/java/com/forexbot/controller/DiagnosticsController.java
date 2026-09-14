package com.forexbot.controller;

import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.RiskGuardrails;
import com.forexbot.dto.TradeDecision;
import com.forexbot.dto.TradeDecisionSignal;
import com.forexbot.service.BotStateManager;
import com.forexbot.service.BridgeWebSocketHandler;
import com.forexbot.service.ExecutionService;
import com.forexbot.service.LlmRouterService;
import com.forexbot.service.MarketDataService;
import com.forexbot.service.RiskFirewall;
import com.forexbot.service.RiskGuardrailStore;
import com.forexbot.service.SignalSchemaValidator;
import com.forexbot.service.TradeSignalValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manual-testing / observability endpoints. Runs the SAME chain the autonomous
 * agent uses, but returns every intermediate stage (bridge status, candle fetch,
 * raw LLM response, parsed signal, risk firewall, guardrail validation and — when
 * {@code dryRun=false} — the real broker dispatch) so you can see precisely why a
 * trade was or was not taken.
 *
 * <p>Guarded by the existing auth interceptor via the {@code /api/bot/**} prefix.</p>
 */
@RestController
@RequestMapping("/api/bot/diag")
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

    private final MarketDataService marketDataService;
    private final LlmRouterService llmRouter;
    private final SignalSchemaValidator schemaValidator;
    private final RiskFirewall riskFirewall;
    private final TradeSignalValidator signalValidator;
    private final ExecutionService executionService;
    private final RiskGuardrailStore guardrailStore;
    private final BotStateManager stateManager;
    private final BridgeWebSocketHandler bridgeHandler;

    public DiagnosticsController(MarketDataService marketDataService,
                                 LlmRouterService llmRouter,
                                 SignalSchemaValidator schemaValidator,
                                 RiskFirewall riskFirewall,
                                 TradeSignalValidator signalValidator,
                                 ExecutionService executionService,
                                 RiskGuardrailStore guardrailStore,
                                 BotStateManager stateManager,
                                 BridgeWebSocketHandler bridgeHandler) {
        this.marketDataService = marketDataService;
        this.llmRouter = llmRouter;
        this.schemaValidator = schemaValidator;
        this.riskFirewall = riskFirewall;
        this.signalValidator = signalValidator;
        this.executionService = executionService;
        this.guardrailStore = guardrailStore;
        this.stateManager = stateManager;
        this.bridgeHandler = bridgeHandler;
    }

    /**
     * Quick "is everything wired up?" snapshot: bridge connectivity, bot state,
     * autonomous flag and the active guardrails. Hit this FIRST.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        RiskGuardrails g = guardrailStore.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bridgeConnected", bridgeHandler.hasConnectedBridge());
        out.put("botRunning", stateManager.isRunning());
        out.put("autonomousEnabled", g.autonomousEnabled());
        out.put("allowedSymbols", g.allowedSymbols());
        out.put("tradesExecutedToday", stateManager.getTradesExecutedToday());
        out.put("dailyPnlUsd", stateManager.getDailyPnlUsd());
        out.put("killSwitchEngaged", stateManager.isKillSwitchEngaged());
        RiskFirewall.RiskCheckResult risk = riskFirewall.evaluate();
        out.put("riskFirewallAllows", risk.allowed());
        out.put("riskFirewallReason", risk.reason());
        return ResponseEntity.ok(out);
    }

    /**
     * End-to-end pipeline probe for a single symbol. Returns every stage so you
     * can pinpoint exactly where a trade is (or isn't) being produced.
     *
     * @param symbol    e.g. EURUSD
     * @param timeframe e.g. M15 (defaults to the agent timeframe)
     * @param candles   number of bars to pull (default 100)
     * @param dryRun    when true (default) the broker dispatch is SKIPPED and only
     *                  reported as "wouldExecute"; set false to actually place the order.
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "M15") String timeframe,
            @RequestParam(defaultValue = "100") int candles,
            @RequestParam(defaultValue = "true") boolean dryRun) {

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("symbol", symbol);
        report.put("timeframe", timeframe);
        report.put("dryRun", dryRun);

        // Stage 0 — environment.
        report.put("bridgeConnected", bridgeHandler.hasConnectedBridge());
        report.put("botRunning", stateManager.isRunning());
        report.put("autonomousEnabled", guardrailStore.get().autonomousEnabled());

        // Stage 1 — risk firewall.
        RiskFirewall.RiskCheckResult risk = riskFirewall.evaluate();
        report.put("stage1_riskFirewallAllows", risk.allowed());
        report.put("stage1_riskFirewallReason", risk.reason());

        // Stage 2 — market data fetch (is the system actually SEEING the market?).
        Optional<MarketDataWindow> windowOpt =
                marketDataService.fetchCandles(symbol, timeframe, candles);
        if (windowOpt.isEmpty() || windowOpt.get().isEmpty()) {
            report.put("stage2_candlesFetched", false);
            report.put("stage2_note",
                    "No candles returned. The bridge/MT5 terminal is likely offline or the "
                            + "symbol is not available. The LLM never gets called without data.");
            return ResponseEntity.ok(report);
        }
        MarketDataWindow window = windowOpt.get();
        report.put("stage2_candlesFetched", true);
        report.put("stage2_candleCount", window.candles() == null ? 0 : window.candles().size());
        report.put("stage2_lastClose", window.lastClose());

        // Stage 3 — raw LLM call (what does the model actually SAY?).
        String rawLlm;
        try {
            rawLlm = llmRouter.analyzeMarketData(window);
        } catch (Exception ex) {
            report.put("stage3_llmCalled", false);
            report.put("stage3_llmError", ex.getMessage());
            report.put("stage3_note",
                    "LLM call failed. Check the selected AI provider, model name, API key and network.");
            return ResponseEntity.ok(report);
        }
        report.put("stage3_llmCalled", true);
        report.put("stage3_rawLlmResponse", rawLlm);

        // Stage 4 — parse + schema validation.
        TradeDecisionSignal signal;
        try {
            signal = schemaValidator.parseAndValidate(rawLlm);
        } catch (Exception ex) {
            report.put("stage4_parsed", false);
            report.put("stage4_parseError", ex.getMessage());
            report.put("stage4_note",
                    "LLM output failed schema validation, so it is treated as HOLD (no trade).");
            return ResponseEntity.ok(report);
        }
        report.put("stage4_parsed", true);
        report.put("stage4_action", signal.action());
        report.put("stage4_confidence", signal.confidenceScore());
        report.put("stage4_volume", signal.volume());
        report.put("stage4_sl", signal.sl());
        report.put("stage4_tp", signal.tp());
        report.put("stage4_actionable", signal.isActionable());

        if (!signal.isActionable()) {
            report.put("outcome", "HOLD");
            report.put("outcomeReason",
                    "LLM chose HOLD — it looked at the market but found no high-conviction setup.");
            return ResponseEntity.ok(report);
        }

        // Stage 5 — guardrail validation + position sizing.
        TradeDecision decision = new TradeDecision(
                symbol,
                "BUY".equalsIgnoreCase(signal.action())
                        ? TradeDecision.Action.BUY : TradeDecision.Action.SELL,
                window.lastClose(), signal.sl(), signal.tp(),
                "diag probe (conf " + signal.confidenceScore() + ")");
        TradeSignalValidator.ValidationResult validation = signalValidator.validate(decision);
        report.put("stage5_guardrailApproved", validation.approved());
        report.put("stage5_guardrailReason", validation.reason());
        report.put("stage5_computedVolumeLots", validation.volume());

        if (!validation.approved()) {
            report.put("outcome", "REJECTED_BY_GUARDRAILS");
            return ResponseEntity.ok(report);
        }

        // Stage 6 — broker dispatch (skipped unless dryRun=false).
        if (dryRun) {
            report.put("stage6_dispatched", false);
            report.put("outcome", "WOULD_EXECUTE");
            report.put("outcomeReason",
                    "All gates passed. Re-run with dryRun=false to place the real order.");
            return ResponseEntity.ok(report);
        }

        com.forexbot.dto.OrderResult execution =
                executionService.dispatch(decision, validation.volume());
        stateManager.incrementTrades();
        report.put("stage6_dispatched", true);
        report.put("stage6_accepted", execution.accepted());
        report.put("stage6_status", execution.status());
        report.put("stage6_brokerRetcode", execution.brokerRetcode());
        report.put("stage6_message", execution.message());
        report.put("outcome", execution.accepted() ? "EXECUTED" : "BROKER_REJECTED");
        log.info("Diag analyze {} -> {} ({})", symbol, report.get("outcome"), execution.status());
        return ResponseEntity.ok(report);
    }
}

