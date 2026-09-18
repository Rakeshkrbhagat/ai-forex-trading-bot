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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service wrapper that securely communicates with the Google Gemini REST API.
 * <p>
 * Authentication uses the API key sourced from the {@code GEMINI_API_KEY}
 * environment variable, passed via the {@code x-goog-api-key} header so it is
 * never leaked into request URLs / logs.
 */
@Service
public class GeminiService implements LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final WebClient geminiWebClient;
    private final GeminiProperties properties;
    private final MarketContextBuilder contextBuilder;
    private final AiSettingsStore aiSettings;

    /** Auto-resolved model name (set when the configured one 404s). */
    private final java.util.concurrent.atomic.AtomicReference<String> resolvedModel =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static final Pattern MODEL_SUGGESTION_PATTERN =
            Pattern.compile("models/([a-zA-Z0-9._-]+)");

    public GeminiService(WebClient geminiWebClient, GeminiProperties properties,
                         MarketContextBuilder contextBuilder, AiSettingsStore aiSettings) {
        this.geminiWebClient = geminiWebClient;
        this.properties = properties;
        this.contextBuilder = contextBuilder;
        this.aiSettings = aiSettings;
    }

    @Override
    public String providerId() {
        return "gemini";
    }

    /** Generic prompt completion used by the multi-provider router. */
    @Override
    public String complete(String prompt, String context) {
        if (!aiSettings.hasApiKey()) {
            throw new IllegalStateException(
                    "API key is not configured; set it in the dashboard AI settings or via env");
        }
        return extractText(generateContent(buildRequestBody(prompt), context));
    }

    /**
     * Sends a structured prompt to Gemini requesting a JSON market-structure
     * analysis for the supplied tick and returns the raw model text response.
     *
     * @param tick the current market tick to analyze
     * @return the model's textual response (expected to contain JSON)
     */
    public String analyzeMarketStructure(MarketTickRequest tick) {
        if (!aiSettings.hasApiKey()) {
            throw new IllegalStateException(
                    "GEMINI_API_KEY is not configured; set it in the dashboard AI settings or via env");
        }
        String prompt = buildMarketStructurePrompt(tick);
        return extractText(generateContent(buildRequestBody(prompt), "market-structure analysis"));
    }

    /**
     * Analyzes a live OHLC candle window (price-action context) and returns the
     * raw JSON trade decision (symbol/action/volume/sl/tp/confidence) from the
     * model acting as an autonomous trading agent.
     */
    public String analyzeMarketData(MarketDataWindow window) {
        if (!aiSettings.hasApiKey()) {
            throw new IllegalStateException(
                    "GEMINI_API_KEY is not configured; set it in the dashboard AI settings or via env");
        }
        String prompt = buildTradeDecisionPrompt(window);
        return extractText(generateContent(buildRequestBody(prompt), "trade-decision analysis"));
    }

    /**
     * Calls the Gemini {@code generateContent} endpoint with the currently
     * resolved model. On a 404 (model not found for this key/version) it queries
     * the ListModels API, auto-selects a working model, caches it, and retries
     * once — so the bot keeps working even when the configured model name is
     * retired or unavailable for the key.
     */
    private Map<String, Object> generateContent(Map<String, Object> requestBody, String context) {
        String model = effectiveModel();
        try {
            return callGenerateWithRetry(model, requestBody, context);
        } catch (WebClientResponseException e) {
            String body = safeBody(e);
            if (e.getStatusCode().value() == 404) {
                log.warn("Model '{}' not found for {} (404). Attempting auto-resolution...",
                        model, context);

                String suggested = extractSuggestedModelFromError(body, model);
                if (suggested != null && !suggested.equals(model)) {
                    log.info("Retrying {} with provider-suggested model '{}'", context, suggested);
                    resolvedModel.set(suggested);
                    try {
                        return callGenerate(suggested, requestBody);
                    } catch (WebClientResponseException e2) {
                        log.warn("Provider-suggested model '{}' failed for {}: {}",
                                suggested, context, e2.getStatusCode());
                    }
                }

                String working = resolveWorkingModel();
                if (working != null && !working.equals(model)) {
                    log.info("Retrying {} with auto-resolved model '{}'", context, working);
                    try {
                        return callGenerate(working, requestBody);
                    } catch (WebClientResponseException e2) {
                        throw new GeminiClientException(
                                "Gemini API error: " + e2.getStatusCode() + " " + safeBody(e2), e2);
                    }
                }
            }
            log.error("Gemini API returned {} for {}: {}", e.getStatusCode(), context, body);
            throw new GeminiClientException("Gemini API error: " + e.getStatusCode()
                    + (body.isBlank() ? "" : " " + body), e);
        } catch (GeminiClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to call Gemini API for {}", context, e);
            throw new GeminiClientException("Failed to call Gemini API: " + e.getMessage(), e);
        }
    }

    /**
     * Retries the call a couple of times on transient overload (503/502/504)
     * with short exponential backoff — these demand spikes usually clear fast.
     */
    private Map<String, Object> callGenerateWithRetry(String model, Map<String, Object> requestBody,
                                                      String context) {
        int maxAttempts = 3;
        for (int attempt = 1; ; attempt++) {
            try {
                return callGenerate(model, requestBody);
            } catch (WebClientResponseException e) {
                int code = e.getStatusCode().value();
                boolean overloaded = code == 503 || code == 502 || code == 504;
                if (!overloaded || attempt >= maxAttempts) {
                    throw e; // non-transient or out of retries → caller handles it.
                }
                long backoffMs = 400L * (1L << (attempt - 1)); // 400ms, 800ms
                log.warn("Transient {} for {} (attempt {}/{}); retrying in {}ms",
                        code, context, attempt, maxAttempts, backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private Map<String, Object> callGenerate(String model, Map<String, Object> requestBody) {
        String path = "/v1beta/models/" + model + ":generateContent";
        return geminiWebClient.post()
                .uri(path)
                .header("x-goog-api-key", aiSettings.effectiveApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .block();
    }

    private String effectiveModel() {
        String cached = resolvedModel.get();
        return (cached != null && !cached.isBlank()) ? cached : aiSettings.effectiveModel();
    }

    /**
     * Queries ListModels and picks a model that supports {@code generateContent},
     * preferring a fast "flash" model, then any generateContent-capable model.
     * The chosen model name is cached for subsequent calls. Returns {@code null}
     * when none can be determined.
     */
    @SuppressWarnings("unchecked")
    private synchronized String resolveWorkingModel() {
        // Another thread may have resolved it while we waited on the lock.
        String cached = resolvedModel.get();
        if (cached != null && !cached.isBlank() && !cached.equals(aiSettings.effectiveModel())) {
            return cached;
        }
        try {
            Map<String, Object> resp = geminiWebClient.get()
                    .uri("/v1beta/models")
                    .header("x-goog-api-key", aiSettings.effectiveApiKey())
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();
            if (resp == null) {
                return null;
            }
            List<Map<String, Object>> models = (List<Map<String, Object>>) resp.get("models");
            if (models == null || models.isEmpty()) {
                return null;
            }
            String best = null;
            for (Map<String, Object> m : models) {
                Object methodsObj = m.get("supportedGenerationMethods");
                boolean supportsGenerate = methodsObj instanceof List<?> methods
                        && methods.stream().anyMatch(x -> "generateContent".equals(String.valueOf(x)));
                if (!supportsGenerate) {
                    continue;
                }
                String name = String.valueOf(m.get("name")); // e.g. "models/gemini-2.5-flash"
                String shortName = name.startsWith("models/") ? name.substring("models/".length()) : name;
                if (shortName.contains("flash")) {
                    best = shortName; // prefer a flash model
                    break;
                }
                if (best == null) {
                    best = shortName; // fallback to first capable model
                }
            }
            if (best != null) {
                log.info("Auto-resolved Gemini model to '{}'", best);
                resolvedModel.set(best);
            }
            return best;
        } catch (Exception ex) {
            log.error("Failed to list Gemini models for auto-resolution: {}", ex.getMessage());
            return null;
        }
    }

    private static String safeBody(WebClientResponseException e) {
        try {
            String b = e.getResponseBodyAsString();
            if (b == null) {
                return "";
            }
            b = b.replaceAll("\\s+", " ").trim();
            return b.length() > 300 ? b.substring(0, 300) + "…" : b;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Builds a strict-JSON prompt from a candle window instructing the model to
     * act as an autonomous trading agent and return a type-safe decision.
     */
    String buildTradeDecisionPrompt(MarketDataWindow window) {
        String style = aiSettings.effectiveTradingStyle();
        String styleGuidance = switch (style == null ? "INTRADAY" : style.toUpperCase()) {
            case "SCALPING" -> "Trade as a SCALPER: target very short-term moves, tight stops and "
                    + "quick take-profits; favour high-probability momentum bursts.";
            case "SWING" -> "Trade as a SWING trader: hold for larger multi-session moves, wider "
                    + "stops/targets aligned with the dominant trend and key levels.";
            default -> "Trade as an INTRADAY trader: capture moves within the session, balancing "
                    + "reward vs. risk and closing exposure intraday.";
        };
        return """
                You are an autonomous forex trading agent and risk-aware strategist.
                Trading style: %s
                %s
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
                - Align stop-loss / take-profit distances with the stated trading style.

                Market data context:
                %s
                """.formatted(style, styleGuidance, contextBuilder.buildPromptPayload(window));
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

    private String extractSuggestedModelFromError(String errorBody, String currentModel) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        // The 404 body typically mentions BOTH the broken model and the
        // recommended replacement, e.g. "models/gemini-2.5-flash is no longer
        // available ... use models/gemini-3.6-flash". Skip the current/broken
        // model and return the actual recommendation.
        Matcher matcher = MODEL_SUGGESTION_PATTERN.matcher(errorBody);
        while (matcher.find()) {
            String suggested = matcher.group(1);
            if (suggested != null && !suggested.isBlank()
                    && (currentModel == null || !suggested.equalsIgnoreCase(currentModel))) {
                return suggested;
            }
        }
        return null;
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

