package com.forexbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forexbot.config.ExecutionProperties;
import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.MarketTickRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Live market-data ingestion. Pulls real-time ticks for a symbol from the local
 * MT5 bridge so the autonomous agent can feed fresh data into the LLM.
 *
 * <p>Transport is selected by {@code execution.transport}: in cloud deployments
 * the bridge is behind NAT, so requests are multiplexed over the WebSocket the
 * bridge dialed out on; local dev can still use outbound REST.</p>
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final ExecutionProperties properties;
    private final WebClient bridgeClient;
    private final BridgeWebSocketHandler bridgeHandler;
    private final ObjectMapper objectMapper;

    public MarketDataService(ExecutionProperties properties,
                             BridgeWebSocketHandler bridgeHandler,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.bridgeHandler = bridgeHandler;
        this.objectMapper = objectMapper;
        WebClient.Builder builder = WebClient.builder().baseUrl(properties.getBridgeUrl());
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
        }
        this.bridgeClient = builder.build();
    }

    /** @return true when quotes/candles should travel over the bridge WebSocket. */
    private boolean useWebSocket() {
        String t = properties.getTransport();
        if ("ws".equalsIgnoreCase(t)) {
            return true;
        }
        if ("rest".equalsIgnoreCase(t)) {
            return false;
        }
        // auto: prefer the WS tunnel whenever a bridge is connected.
        return bridgeHandler.hasConnectedBridge();
    }

    /** Fetches the latest tick for a symbol, or empty if unavailable. */
    public Optional<MarketTickRequest> fetchTick(String symbol) {
        if (useWebSocket()) {
            return fetchTickOverWs(symbol);
        }
        try {
            MarketTickRequest tick = bridgeClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/quote").queryParam("symbol", symbol).build())
                    .retrieve()
                    .bodyToMono(MarketTickRequest.class)
                    .timeout(Duration.ofSeconds(Math.max(3, properties.getTimeoutSeconds())))
                    .block();
            return Optional.ofNullable(tick);
        } catch (Exception ex) {
            log.warn("Failed to fetch market data for {}: {}", symbol, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<MarketTickRequest> fetchTickOverWs(String symbol) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        Map<String, Object> resp = bridgeHandler.request("QUOTE_REQUEST", payload,
                Math.max(3, properties.getTimeoutSeconds()) * 1000L);
        if (resp == null) {
            return Optional.empty();
        }
        // The bridge may wrap the tick in a "data" field or return it flat.
        Object data = resp.getOrDefault("data", resp);
        try {
            return Optional.ofNullable(objectMapper.convertValue(data, MarketTickRequest.class));
        } catch (Exception ex) {
            log.warn("Failed to parse WS quote for {}: {}", symbol, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetches a recent OHLC candle window for a symbol to build the LLM context.
     */
    public Optional<MarketDataWindow> fetchCandles(String symbol, String timeframe, int nBars) {
        if (useWebSocket()) {
            return fetchCandlesOverWs(symbol, timeframe, nBars);
        }
        try {
            MarketDataWindow window = bridgeClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/candles")
                            .queryParam("symbol", symbol)
                            .queryParam("timeframe", timeframe)
                            .queryParam("nBars", nBars)
                            .build())
                    .retrieve()
                    .bodyToMono(MarketDataWindow.class)
                    .timeout(Duration.ofSeconds(Math.max(5, properties.getTimeoutSeconds())))
                    .block();
            return Optional.ofNullable(window);
        } catch (Exception ex) {
            log.warn("Failed to fetch candles for {}: {}", symbol, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<MarketDataWindow> fetchCandlesOverWs(String symbol, String timeframe, int nBars) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        payload.put("timeframe", timeframe);
        payload.put("nBars", nBars);
        Map<String, Object> resp = bridgeHandler.request("CANDLES_REQUEST", payload,
                Math.max(5, properties.getTimeoutSeconds()) * 1000L);
        if (resp == null) {
            return Optional.empty();
        }
        Object data = resp.getOrDefault("data", resp);
        try {
            return Optional.ofNullable(objectMapper.convertValue(data, MarketDataWindow.class));
        } catch (Exception ex) {
            log.warn("Failed to parse WS candles for {}: {}", symbol, ex.getMessage());
            return Optional.empty();
        }
    }
}

