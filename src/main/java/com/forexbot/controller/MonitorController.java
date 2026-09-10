package com.forexbot.controller;

import com.forexbot.service.BridgeWebSocketHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only monitoring endpoints consumed by the Streamlit dashboard:
 * live bridge telemetry, open positions and the AI activity feed.
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final BridgeWebSocketHandler bridgeHandler;

    public MonitorController(BridgeWebSocketHandler bridgeHandler) {
        this.bridgeHandler = bridgeHandler;
    }

    /** Bridge connection state + latest account snapshot (balance/equity/margin). */
    @GetMapping("/telemetry")
    public Map<String, Object> telemetry() {
        Map<String, Object> body = new LinkedHashMap<>();
        // "connected" reflects a LIVE MT5 terminal/account session (what the
        // dashboard cares about), not merely the relay socket being open.
        boolean bridgeOnline = bridgeHandler.hasConnectedBridge();
        boolean mt5Connected = bridgeHandler.isMt5Connected();
        body.put("connected", bridgeOnline && mt5Connected);
        body.put("bridgeOnline", bridgeOnline);
        body.put("mt5Connected", mt5Connected);
        body.put("account", bridgeHandler.getLastAccount());
        return body;
    }

    /** Latest open positions streamed by the bridge. */
    @GetMapping("/positions")
    public List<Map<String, Object>> positions() {
        return bridgeHandler.getLastPositions();
    }

    /** AI activity / decision feed (placeholder until wired to the agent). */
    @GetMapping("/activity")
    public List<Map<String, Object>> activity(@RequestParam(defaultValue = "40") int limit) {
        return List.of();
    }
}

