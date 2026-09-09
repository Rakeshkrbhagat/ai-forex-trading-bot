package com.forexbot.service;

import com.forexbot.config.ExecutionProperties;
import com.forexbot.dto.MarketTickRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Live market-data ingestion. Pulls real-time ticks for a symbol from the local
 * MT5 bridge so the autonomous agent can feed fresh data into the LLM.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final ExecutionProperties properties;
    private final WebClient bridgeClient;

    public MarketDataService(ExecutionProperties properties) {
        this.properties = properties;
        WebClient.Builder builder = WebClient.builder().baseUrl(properties.getBridgeUrl());
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
        }
        this.bridgeClient = builder.build();
    }

    /** Fetches the latest tick for a symbol, or empty if unavailable. */
    public Optional<MarketTickRequest> fetchTick(String symbol) {
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
}

