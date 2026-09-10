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
        body.put("connected", bridgeHandler.hasConnectedBridge());
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

