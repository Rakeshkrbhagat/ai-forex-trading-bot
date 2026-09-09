package com.forexbot.controller;

import com.forexbot.dto.BotStatus;
import com.forexbot.dto.MarketTickRequest;
import com.forexbot.dto.TradeDecision;
import com.forexbot.service.BotStateManager;
import com.forexbot.service.TickPipelineService;
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
 * state manager. Exposes /start, /stop, /status and the unified /tick
 * pipeline endpoint.
 */
@RestController
@RequestMapping("/api/bot")
public class TradingBotController {

    private final BotStateManager stateManager;
    private final TickPipelineService tickPipeline;

    public TradingBotController(BotStateManager stateManager,
                                TickPipelineService tickPipeline) {
        this.stateManager = stateManager;
        this.tickPipeline = tickPipeline;
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
     * Unified tick pipeline: validates risk limits, queries Gemini, increments
     * thread-safe trade counters on confirmed trends, and returns a structured
     * {@link TradeDecision}. Risk-blocked ticks return 423 LOCKED.
     */
    @PostMapping("/tick")
    public ResponseEntity<TradeDecision> processTick(@Valid @RequestBody MarketTickRequest tick) {
        TickPipelineService.PipelineResult result = tickPipeline.process(tick);
        if (result.riskBlocked()) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(result.decision());
        }
        return ResponseEntity.ok(result.decision());
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

