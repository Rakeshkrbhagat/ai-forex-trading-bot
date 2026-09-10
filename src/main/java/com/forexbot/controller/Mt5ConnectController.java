package com.forexbot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forexbot.dto.Mt5Credentials;
import com.forexbot.service.BridgeWebSocketHandler;
import com.forexbot.service.Mt5CredentialStore;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accepts MT5 broker credentials from the dashboard and stores them in memory
 * for dynamic per-execution routing. When a local bridge is connected over the
 * WebSocket pipeline, the credentials are also pushed down so the bridge can
 * (re)initialize the MT5 terminal session.
 */
@RestController
@RequestMapping("/api/mt5")
public class Mt5ConnectController {

    private static final Logger log = LoggerFactory.getLogger(Mt5ConnectController.class);

    private final Mt5CredentialStore credentialStore;
    private final BridgeWebSocketHandler bridgeHandler;
    private final ObjectMapper objectMapper;

    public Mt5ConnectController(Mt5CredentialStore credentialStore,
                                BridgeWebSocketHandler bridgeHandler,
                                ObjectMapper objectMapper) {
        this.credentialStore = credentialStore;
        this.bridgeHandler = bridgeHandler;
        this.objectMapper = objectMapper;
    }

    /** Store credentials and, if possible, forward them to a connected bridge. */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(@Valid @RequestBody Mt5Credentials credentials) {
        credentialStore.save(credentials);

        boolean pushedToBridge = false;
        try {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("type", "CONNECT");
            command.put("login", credentials.login());
            command.put("password", credentials.password());
            command.put("server", credentials.server());
            pushedToBridge = bridgeHandler.sendCommand(null, objectMapper.writeValueAsString(command));
        } catch (Exception ex) {
            log.warn("Failed to forward credentials to bridge: {}", ex.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "connected");
        body.put("login", credentials.login());
        body.put("server", credentials.server());
        body.put("bridgeConnected", bridgeHandler.hasConnectedBridge());
        body.put("credentialsForwarded", pushedToBridge);
        return ResponseEntity.ok(body);
    }

    /** Reports whether credentials are configured and a bridge is online. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", credentialStore.isConfigured());
        body.put("bridgeConnected", bridgeHandler.hasConnectedBridge());
        credentialStore.get().ifPresent(c -> {
            body.put("login", c.login());
            body.put("server", c.server());
        });
        return ResponseEntity.ok(body);
    }

    /** Clears stored credentials. */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        credentialStore.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "disconnected");
        return ResponseEntity.ok(body);
    }
}

