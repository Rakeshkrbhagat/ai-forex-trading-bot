package com.forexbot.controller;

import com.forexbot.dto.BotStatus;
import com.forexbot.dto.MarketTickRequest;
import com.forexbot.dto.TradeDecision;
import com.forexbot.service.BotStateManager;
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

    public TradingBotController(BotStateManager stateManager, RiskFirewall riskFirewall) {
        this.stateManager = stateManager;
        this.riskFirewall = riskFirewall;
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

        // Risk checks passed -> a trade may be evaluated/executed here.
        TradeDecision decision = new TradeDecision(
                tick.currencyPair(),
                TradeDecision.Action.HOLD,
                tick.ask(),
                null,
                null,
                "Tick accepted by risk firewall"
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

