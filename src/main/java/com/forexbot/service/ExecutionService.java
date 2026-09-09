package com.forexbot.service;

import com.forexbot.config.ExecutionProperties;
import com.forexbot.dto.OrderRequest;
import com.forexbot.dto.OrderResult;
import com.forexbot.dto.TradeDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Broker execution gateway. Dispatches risk-validated BUY/SELL decisions to the
 * local Python MT5 bridge over REST and normalizes broker responses, including
 * rejection retcodes, slippage limits and connection timeouts.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionProperties properties;
    private final WebClient bridgeClient;

    public ExecutionService(ExecutionProperties properties) {
        this.properties = properties;
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.getBridgeUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
        }
        this.bridgeClient = builder.build();
    }

    /**
     * Dispatches a confirmed {@link TradeDecision} to the MT5 bridge.
     *
     * @param decision a BUY or SELL decision produced by the pipeline.
     * @param volume   order volume in lots (falls back to the configured default
     *                 when non-positive).
     * @return a normalized {@link OrderResult}; never {@code null}.
     */
    public OrderResult dispatch(TradeDecision decision, double volume) {
        if (decision == null || decision.action() == TradeDecision.Action.HOLD) {
            return OrderResult.rejected("SKIPPED", "No actionable order for HOLD decision");
        }

        double lots = volume > 0 ? volume : properties.getDefaultVolumeLots();
        OrderRequest order = new OrderRequest(
                decision.currencyPair(),
                decision.action().name(),
                lots,
                properties.getStopLossPips(),
                properties.getTakeProfitPips(),
                decision.entryPrice(),
                decision.stopLossPrice(),
                decision.takeProfitPrice(),
                properties.getMaxSlippagePoints(),
                properties.getMagicNumber(),
                "forexbot:" + decision.rationale()
        );

        if (!properties.isEnabled()) {
            log.warn("Execution DISABLED - simulating dispatch for {} {} {} lots",
                    order.side(), order.symbol(), order.volume());
            return new OrderResult(true, "SIMULATED", 0, null, decision.entryPrice(),
                    lots, 0.0, "Execution disabled; order simulated", java.time.Instant.now());
        }

        log.info("Dispatching {} {} {} lots to MT5 bridge (SL={} pips)",
                order.side(), order.symbol(), order.volume(), order.stopLossPips());

        try {
            OrderResult result = bridgeClient.post()
                    .uri("/order")
                    .bodyValue(order)
                    .retrieve()
                    .bodyToMono(OrderResult.class)
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (result == null) {
                log.error("MT5 bridge returned an empty body for {} {}", order.side(), order.symbol());
                return OrderResult.rejected("EMPTY_RESPONSE", "Bridge returned no response body");
            }

            if (result.accepted()) {
                log.info("Order FILLED: ticket={} price={} slippage={} pips retcode={}",
                        result.orderTicket(), result.executedPrice(),
                        result.slippagePips(), result.brokerRetcode());
            } else {
                log.warn("Order REJECTED by broker: status={} retcode={} msg={}",
                        result.status(), result.brokerRetcode(), result.message());
            }
            return result;

        } catch (WebClientResponseException ex) {
            log.error("MT5 bridge HTTP {} for {} {}: {}",
                    ex.getStatusCode(), order.side(), order.symbol(), ex.getResponseBodyAsString());
            return OrderResult.rejected("BROKER_ERROR",
                    "Bridge HTTP " + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString());
        } catch (WebClientRequestException ex) {
            log.error("MT5 bridge connection failure for {} {}: {}",
                    order.side(), order.symbol(), ex.getMessage());
            return OrderResult.rejected("CONNECTION_ERROR",
                    "Failed to reach MT5 bridge: " + ex.getMessage());
        } catch (Exception ex) {
            Throwable cause = ex.getCause();
            if (ex instanceof IllegalStateException && cause instanceof TimeoutException
                    || cause instanceof TimeoutException) {
                log.error("MT5 bridge timed out after {}s for {} {}",
                        properties.getTimeoutSeconds(), order.side(), order.symbol());
                return OrderResult.rejected("TIMEOUT",
                        "Bridge timed out after " + properties.getTimeoutSeconds() + "s");
            }
            log.error("Unexpected execution error for {} {}: {}",
                    order.side(), order.symbol(), ex.getMessage(), ex);
            return OrderResult.rejected("EXECUTION_ERROR", ex.getMessage());
        }
    }
}

