package com.forexbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forexbot.config.ExecutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket endpoint the local MT5 bridge dials out to (it lives behind NAT).
 *
 * <p>The bridge connects to {@code /ws/bridge}, announces itself with a
 * {@code HELLO} frame, then streams {@code TELEMETRY} snapshots and
 * {@code EXECUTION_RECEIPT}s. The backend can push risk-validated trade
 * commands back down the same socket via {@link #sendCommand}.</p>
 */
@Component
public class BridgeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BridgeWebSocketHandler.class);

    /** bridgeId -> live session. Last writer wins if a bridge reconnects. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** Latest account snapshot streamed by the bridge (balance, equity, ...). */
    private final AtomicReference<Map<String, Object>> lastAccount = new AtomicReference<>(Map.of());

    /** Latest open positions streamed by the bridge, if any. */
    private final AtomicReference<List<Map<String, Object>>> lastPositions = new AtomicReference<>(List.of());

    /** Whether the bridge reports a live MT5 terminal/account session. */
    private final AtomicReference<Boolean> mt5Connected = new AtomicReference<>(false);

    /** requestId -> future awaiting the bridge's correlated response. */
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    private final ExecutionProperties properties;
    private final ObjectMapper objectMapper;

    public BridgeWebSocketHandler(ExecutionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("MT5 bridge WebSocket connected: id={} remote={}",
                session.getId(), session.getRemoteAddress());
        // Provisionally index by session id until a HELLO frame supplies bridgeId.
        sessions.put(session.getId(), session);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(payload, Map.class);
        } catch (Exception ex) {
            log.warn("Ignoring non-JSON bridge message: {}", ex.getMessage());
            return;
        }

        String type = String.valueOf(parsed.getOrDefault("type", "")).toUpperCase();
        Object bridgeIdObj = parsed.get("bridgeId");
        String bridgeId = bridgeIdObj == null ? null : String.valueOf(bridgeIdObj);

        if (bridgeId != null && !bridgeId.isBlank()) {
            // Re-index under the stable bridgeId and drop the provisional key.
            sessions.remove(session.getId());
            sessions.put(bridgeId, session);
        }

        // Capture any account/position snapshot regardless of frame type.
        Object account = parsed.get("account");
        if (account instanceof Map<?, ?> accountMap) {
            lastAccount.set((Map<String, Object>) accountMap);
        }
        Object positions = parsed.get("positions");
        if (positions instanceof List<?> positionList) {
            lastPositions.set((List<Map<String, Object>>) positionList);
        }
        // The bridge reports MT5 terminal/account readiness via a 'connected' flag.
        Object connectedFlag = parsed.get("connected");
        if (connectedFlag instanceof Boolean b) {
            mt5Connected.set(b);
        }

        // Resolve any pending request/response correlation (quotes, candles, orders).
        Object requestIdObj = parsed.get("requestId");
        if (requestIdObj != null) {
            CompletableFuture<Map<String, Object>> future = pending.remove(String.valueOf(requestIdObj));
            if (future != null) {
                future.complete(parsed);
            }
        }

        switch (type) {
            case "HELLO" -> log.info("Bridge registered: bridgeId={} payload={}", bridgeId, payload);
            case "TELEMETRY" -> log.debug("Bridge telemetry [{}]: {}", bridgeId, payload);
            case "EXECUTION_RECEIPT" -> log.info("Execution receipt [{}]: {}", bridgeId, payload);
            default -> log.debug("Bridge message [{}] type={} payload={}", bridgeId, type, payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.values().removeIf(s -> s.getId().equals(session.getId()));
        if (sessions.isEmpty()) {
            // No bridge online: reset telemetry so the dashboard reflects reality.
            lastAccount.set(Map.of());
            lastPositions.set(List.of());
            mt5Connected.set(false);
        }
        log.info("MT5 bridge WebSocket closed: id={} status={}", session.getId(), status);
    }

    /** @return the latest account snapshot (balance/equity/margin), never null. */
    public Map<String, Object> getLastAccount() {
        return lastAccount.get();
    }

    /** @return whether the bridge reports a live MT5 terminal/account session. */
    public boolean isMt5Connected() {
        return Boolean.TRUE.equals(mt5Connected.get());
    }

    /** @return the latest open positions streamed by the bridge, never null. */
    public List<Map<String, Object>> getLastPositions() {
        return lastPositions.get();
    }

    /**
     * Push a risk-validated command (JSON) to a connected bridge.
     *
     * @param bridgeId    target bridge; when {@code null}/blank the first
     *                    available session is used.
     * @param jsonCommand the serialized command payload.
     * @return {@code true} when the command was written to an open session.
     */
    public boolean sendCommand(String bridgeId, String jsonCommand) {
        WebSocketSession session = (bridgeId != null && !bridgeId.isBlank())
                ? sessions.get(bridgeId)
                : sessions.values().stream().filter(WebSocketSession::isOpen).findFirst().orElse(null);

        if (session == null || !session.isOpen()) {
            log.warn("No open MT5 bridge session for bridgeId={}", bridgeId);
            return false;
        }
        try {
            session.sendMessage(new TextMessage(jsonCommand));
            return true;
        } catch (IOException ex) {
            log.error("Failed to send command to bridge {}: {}", bridgeId, ex.getMessage());
            return false;
        }
    }

    /** @return {@code true} if at least one bridge session is currently open. */
    public boolean hasConnectedBridge() {
        return sessions.values().stream().anyMatch(WebSocketSession::isOpen);
    }

    /**
     * Send a typed request down the bridge WebSocket and block for its correlated
     * response. This is the cloud-safe transport: the bridge lives behind NAT and
     * cannot be reached via outbound REST from the Render container, so quotes,
     * candles and orders are multiplexed over the socket the bridge dialed out on.
     *
     * @param type      request type the bridge understands (e.g. {@code QUOTE_REQUEST}).
     * @param payload   request fields (may be {@code null}).
     * @param timeoutMs how long to wait for the correlated response.
     * @return the response map, or {@code null} on timeout / no bridge / error.
     */
    public Map<String, Object> request(String type, Map<String, Object> payload, long timeoutMs) {
        if (!hasConnectedBridge()) {
            log.warn("No bridge connected; cannot service {} request", type);
            return null;
        }
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> message = new LinkedHashMap<>();
        if (payload != null) {
            message.putAll(payload);
        }
        message.put("type", type);
        message.put("requestId", requestId);

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            String json = objectMapper.writeValueAsString(message);
            if (!sendCommand(null, json)) {
                pending.remove(requestId);
                return null;
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.warn("Bridge {} request timed out/failed: {}", type, ex.getMessage());
            return null;
        } finally {
            pending.remove(requestId);
        }
    }

    /**
     * Optional shared-secret check used by the handshake interceptor. Accepts the
     * connection when no api key is configured (dev mode).
     */
    boolean isAuthorized(String bearerToken) {
        String expected = properties.getApiKey();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equals(bearerToken);
    }
}

