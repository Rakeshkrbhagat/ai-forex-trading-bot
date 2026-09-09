package com.forexbot.service;

import com.forexbot.config.GeminiProperties;
import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.MarketTickRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Service wrapper that securely communicates with the Google Gemini REST API.
 * <p>
 * Authentication uses the API key sourced from the {@code GEMINI_API_KEY}
 * environment variable, passed via the {@code x-goog-api-key} header so it is
 * never leaked into request URLs / logs.
 */
@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final WebClient geminiWebClient;
    private final GeminiProperties properties;
    private final MarketContextBuilder contextBuilder;

    public GeminiService(WebClient geminiWebClient, GeminiProperties properties,
                         MarketContextBuilder contextBuilder) {
        this.geminiWebClient = geminiWebClient;
        this.properties = properties;
        this.contextBuilder = contextBuilder;
    }

    /**
     * Sends a structured prompt to Gemini requesting a JSON market-structure
     * analysis for the supplied tick and returns the raw model text response.
     *
     * @param tick the current market tick to analyze
     * @return the model's textual response (expected to contain JSON)
     */
    public String analyzeMarketStructure(MarketTickRequest tick) {
        if (!properties.hasApiKey()) {
            throw new IllegalStateException(
                    "GEMINI_API_KEY is not configured; cannot call Gemini API");
        }

        String prompt = buildMarketStructurePrompt(tick);
        Map<String, Object> requestBody = buildRequestBody(prompt);

        String path = "/v1beta/models/" + properties.getModel() + ":generateContent";

        try {
            Map<String, Object> response = geminiWebClient.post()
                    .uri(path)
                    // Secure authentication: API key passed as a header, not in the URL.
                    .header("x-goog-api-key", properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            return extractText(response);
        } catch (WebClientResponseException e) {
            log.error("Gemini API returned {} for market-structure analysis",
                    e.getStatusCode(), e);
            throw new GeminiClientException(
                    "Gemini API error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Failed to call Gemini API", e);
            throw new GeminiClientException("Failed to call Gemini API", e);
        }
    }

    /**
     * Analyzes a live OHLC candle window (price-action context) and returns the
     * raw JSON trade decision (symbol/action/volume/sl/tp/confidence) from the
     * model acting as an autonomous trading agent.
     */
    public String analyzeMarketData(MarketDataWindow window) {
        if (!properties.hasApiKey()) {
            throw new IllegalStateException(
                    "GEMINI_API_KEY is not configured; cannot call Gemini API");
        }

        String prompt = buildTradeDecisionPrompt(window);
        Map<String, Object> requestBody = buildRequestBody(prompt);
        String path = "/v1beta/models/" + properties.getModel() + ":generateContent";

        try {
            Map<String, Object> response = geminiWebClient.post()
                    .uri(path)
                    .header("x-goog-api-key", properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();
            return extractText(response);
        } catch (WebClientResponseException e) {
            log.error("Gemini API returned {} for trade-decision analysis", e.getStatusCode(), e);
            throw new GeminiClientException("Gemini API error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Failed to call Gemini API", e);
            throw new GeminiClientException("Failed to call Gemini API", e);
        }
    }

    /**
     * Builds a strict-JSON prompt from a candle window instructing the model to
     * act as an autonomous trading agent and return a type-safe decision.
     */
    String buildTradeDecisionPrompt(MarketDataWindow window) {
        return """
                You are an autonomous forex trading agent and risk-aware strategist.
                Analyze the recent price action and respond with STRICT JSON only,
                no markdown, no commentary. Use this exact schema:
                {
                  "symbol": string,
                  "action": "BUY" | "SELL" | "HOLD",
                  "volume": number,            // order size in lots, e.g. 0.10
                  "sl": number,                // stop-loss price
                  "tp": number,                // take-profit price
                  "confidenceScore": number    // 0.0 - 1.0
                }
                Rules:
                - Only signal BUY or SELL on a clear, high-conviction setup; otherwise HOLD.
                - For HOLD, set volume, sl and tp to 0.
                - confidenceScore reflects conviction from 0.0 (none) to 1.0 (certain).

                Market data context:
                %s
                """.formatted(contextBuilder.buildPromptPayload(window));
    }

    /**
     * Builds a structured prompt instructing Gemini to return strictly JSON
     * market-structure analysis.
     */
    String buildMarketStructurePrompt(MarketTickRequest tick) {
        return """
                You are an expert forex market-structure analyst.
                Analyze the following market tick and respond with STRICT JSON only,
                no markdown, no commentary. Use this exact schema:
                {
                  "currencyPair": string,
                  "structure": "TRENDING" | "SIDEWAYS",
                  "direction": "BUY" | "SELL" | "HOLD",
                  "keySupport": number,
                  "keyResistance": number,
                  "confidence": number,
                  "rationale": string
                }
                Rules: if the market is SIDEWAYS, direction MUST be HOLD.
                If the market is TRENDING, direction should be BUY or SELL.

                Market tick:
                - currencyPair: %s
                - bid: %s
                - ask: %s
                - timestamp: %s
                """.formatted(
                tick.currencyPair(),
                tick.bid(),
                tick.ask(),
                tick.timestamp());
    }

    /** Assembles the Gemini generateContent request payload. */
    private Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                ),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "responseMimeType", "application/json"
                )
        );
    }

    /** Extracts the first candidate's text from the Gemini response payload. */
    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> response) {
        if (response == null) {
            throw new GeminiClientException("Empty response from Gemini API");
        }
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new GeminiClientException("No candidates returned by Gemini API");
        }
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        return String.valueOf(parts.get(0).get("text"));
    }

    /** Raised when the Gemini API cannot be reached or returns an error. */
    public static class GeminiClientException extends RuntimeException {
        public GeminiClientException(String message) {
            super(message);
        }

        public GeminiClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

