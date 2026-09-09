package com.forexbot.controller;

import com.forexbot.dto.BotStatus;
import com.forexbot.dto.MarketAnalysis;
import com.forexbot.dto.MarketTickRequest;
import com.forexbot.dto.TradeDecision;
import com.forexbot.service.BotStateManager;
import com.forexbot.service.GeminiService;
import com.forexbot.service.MarketStructureFilter;
import com.forexbot.service.RiskFirewall;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lifecycle endpoints for the trading bot backed by a thread-safe atomic
 * state manager. Exposes /start, /stop, /status and /tick. Every /tick is
 * guarded by the non-negotiable {@link RiskFirewall}.
 */
@RestController
@RequestMapping("/api/bot")
public class TradingBotController {

    private final BotStateManager stateManager;
    private final RiskFirewall riskFirewall;
    private final GeminiService geminiService;
    private final MarketStructureFilter marketStructureFilter;

    public TradingBotController(BotStateManager stateManager,
                                RiskFirewall riskFirewall,
                                GeminiService geminiService,
                                MarketStructureFilter marketStructureFilter) {
        this.stateManager = stateManager;
        this.riskFirewall = riskFirewall;
        this.geminiService = geminiService;
        this.marketStructureFilter = marketStructureFilter;
    }

    /**
     * Starts the bot, initializing the running flag to true and resetting
     * daily counters.
     */
    @PostMapping("/start")
    public ResponseEntity<BotStatus> start(
            @RequestParam(name = "accountId", required = false) String accountId) {
        stateManager.start(accountId);
        return ResponseEntity.ok(snapshot());
    }

    /** Stops the bot, toggling the running state to false. */
    @PostMapping("/stop")
    public ResponseEntity<BotStatus> stop() {
        stateManager.stop();
        return ResponseEntity.ok(snapshot());
    }

    /**
     * Returns the current execution status, trades count and running P&L.
     */
    @GetMapping("/status")
    public ResponseEntity<BotStatus> status() {
        return ResponseEntity.ok(snapshot());
    }

    /**
     * Processes an incoming market tick. The hardcoded risk firewall is
     * evaluated first; if any non-negotiable limit is breached the bot halts
     * (or the kill switch trips) and the tick is rejected with 423 LOCKED.
     */
    @PostMapping("/tick")
    public ResponseEntity<TradeDecision> processTick(@Valid @RequestBody MarketTickRequest tick) {
        RiskFirewall.RiskCheckResult check = riskFirewall.evaluate();
        if (!check.allowed()) {
            TradeDecision blocked = new TradeDecision(
                    tick.currencyPair(),
                    TradeDecision.Action.HOLD,
                    null,
                    null,
                    null,
                    "Risk firewall blocked trade: " + check.reason()
            );
            return ResponseEntity.status(HttpStatus.LOCKED).body(blocked);
        }

        // Risk checks passed -> ask Gemini for market-structure analysis.
        String rawAnalysis = geminiService.analyzeMarketStructure(tick);
        MarketAnalysis analysis = marketStructureFilter.parse(rawAnalysis);
        MarketStructureFilter.FilterResult filter = marketStructureFilter.apply(analysis);

        // Binary market filter: SIDEWAYS -> HOLD (blocked); TRENDING BUY/SELL -> proceed.
        if (!filter.proceed()) {
            TradeDecision hold = new TradeDecision(
                    tick.currencyPair(),
                    TradeDecision.Action.HOLD,
                    null,
                    null,
                    null,
                    filter.reason()
            );
            return ResponseEntity.ok(hold);
        }

        TradeDecision.Action action = filter.action() == MarketAnalysis.Direction.BUY
                ? TradeDecision.Action.BUY
                : TradeDecision.Action.SELL;

        TradeDecision decision = new TradeDecision(
                tick.currencyPair(),
                action,
                tick.ask(),
                analysis.keySupport(),
                analysis.keyResistance(),
                filter.reason()
        );
        return ResponseEntity.ok(decision);
    }

    private BotStatus snapshot() {
        return new BotStatus(
                stateManager.getAccountId(),
                stateManager.isRunning(),
                stateManager.getTradesExecutedToday(),
                stateManager.getDailyPnlUsd(),
                stateManager.getLastUpdated()
        );
    }
}

