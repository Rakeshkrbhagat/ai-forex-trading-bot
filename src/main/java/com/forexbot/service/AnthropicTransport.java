package com.forexbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transport for Anthropic Claude, which uses the {@code /v1/messages} API with
 * an {@code x-api-key} header and an {@code anthropic-version} header.
 */
@Service
public class AnthropicTransport implements LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(AnthropicTransport.class);
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final Set<String> SUPPORTED = Set.of("claude", "anthropic");

    private final AiSettingsStore aiSettings;
    private final WebClient webClient = WebClient.builder().build();

    public AnthropicTransport(AiSettingsStore aiSettings) {
        this.aiSettings = aiSettings;
    }

    @Override
    public String providerId() {
        return "claude";
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && SUPPORTED.contains(provider.trim().toLowerCase());
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String prompt, String context) {
        String apiKey = aiSettings.effectiveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "API key is not configured; set it in the dashboard AI settings.");
        }
        String model = aiSettings.effectiveModel();

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
        );

        try {
            Map<String, Object> resp = webClient.post()
                    .uri(ENDPOINT)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (resp == null) {
                throw new IllegalStateException("Empty response from Claude");
            }
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
            if (content == null || content.isEmpty()) {
                throw new IllegalStateException("No content returned by Claude");
            }
            return String.valueOf(content.get(0).get("text"));
        } catch (WebClientResponseException e) {
            String msg = "Claude API error: " + e.getStatusCode() + " " + safeBody(e);
            log.error("{} for {}", msg, context);
            throw new IllegalStateException(msg, e);
        }
    }

    private static String safeBody(WebClientResponseException e) {
        try {
            String b = e.getResponseBodyAsString();
            b = (b == null) ? "" : b.replaceAll("\\s+", " ").trim();
            return b.length() > 300 ? b.substring(0, 300) + "…" : b;
        } catch (Exception ignored) {
            return "";
        }
    }
}

