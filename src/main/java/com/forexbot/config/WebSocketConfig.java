package com.forexbot.config;

import com.forexbot.service.BridgeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the MT5 bridge WebSocket endpoint.
 *
 * <p>The local Python bridge connects out to {@code wss://<host>/ws/bridge}
 * (derived automatically from {@code BACKEND_URL} on the bridge side). Any
 * origin is allowed because the bridge is a headless client, not a browser.</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final BridgeWebSocketHandler bridgeWebSocketHandler;

    public WebSocketConfig(BridgeWebSocketHandler bridgeWebSocketHandler) {
        this.bridgeWebSocketHandler = bridgeWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(bridgeWebSocketHandler, "/ws/bridge")
                .setAllowedOriginPatterns("*");
    }
}

