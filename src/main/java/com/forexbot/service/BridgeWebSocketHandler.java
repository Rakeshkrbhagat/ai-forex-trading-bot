package com.forexbot.service;

import com.forexbot.config.ExecutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final ExecutionProperties properties;

    public BridgeWebSocketHandler(ExecutionProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("MT5 bridge WebSocket connected: id={} remote={}",
                session.getId(), session.getRemoteAddress());
        // Provisionally index by session id until a HELLO frame supplies bridgeId.
        sessions.put(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        String type = extractField(payload, "type");
        String bridgeId = extractField(payload, "bridgeId");

        if (bridgeId != null && !bridgeId.isBlank()) {
            // Re-index under the stable bridgeId and drop the provisional key.
            sessions.remove(session.getId());
            sessions.put(bridgeId, session);
        }

        switch (type == null ? "" : type.toUpperCase()) {
            case "HELLO" -> log.info("Bridge registered: bridgeId={} payload={}", bridgeId, payload);
            case "TELEMETRY" -> log.debug("Bridge telemetry [{}]: {}", bridgeId, payload);
            case "EXECUTION_RECEIPT" -> log.info("Execution receipt [{}]: {}", bridgeId, payload);
            default -> log.debug("Bridge message [{}] type={} payload={}", bridgeId, type, payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.values().removeIf(s -> s.getId().equals(session.getId()));
        log.info("MT5 bridge WebSocket closed: id={} status={}", session.getId(), status);
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

    /** Minimal, dependency-free extraction of a top-level string JSON field. */
    private static String extractField(String json, String field) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + field + "\"";
        int keyIdx = json.indexOf(needle);
        if (keyIdx < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyIdx + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return null; // non-string value; not needed for our routing fields.
        }
        int start = i + 1;
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
}

