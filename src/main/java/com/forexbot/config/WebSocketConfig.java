package com.forexbot.config;

import com.forexbot.service.BridgeWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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

    /**
     * Tunes the underlying servlet WebSocket container so the long-lived bridge
     * session is not closed prematurely. Without a generous idle timeout the
     * platform/container can drop the socket between telemetry frames, which the
     * dashboard then shows as "Bridge Disconnected".
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 5 minutes of idle tolerance (bridge pings/telemetry are far more frequent).
        container.setMaxSessionIdleTimeout(300_000L);
        container.setMaxTextMessageBufferSize(512 * 1024);
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        return container;
    }
}

