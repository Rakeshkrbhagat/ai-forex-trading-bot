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
 * Transport for OpenAI-compatible chat-completions providers. A single
 * implementation covers OpenAI (ChatGPT), DeepSeek, xAI (Grok) and Mistral,
 * since they all speak the same {@code /chat/completions} protocol with a
 * bearer API key — only the base URL differs.
 */
@Service
public class OpenAiChatTransport implements LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatTransport.class);

    private static final Set<String> SUPPORTED =
            Set.of("openai", "chatgpt", "gpt", "deepseek", "grok", "xai", "mistral");

    private final AiSettingsStore aiSettings;
    private final WebClient webClient = WebClient.builder().build();

    public OpenAiChatTransport(AiSettingsStore aiSettings) {
        this.aiSettings = aiSettings;
    }

    @Override
    public String providerId() {
        return "openai";
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && SUPPORTED.contains(provider.trim().toLowerCase());
    }

    /** Resolves the chat-completions endpoint for the active provider. */
    private String endpointFor(String provider) {
        String p = provider == null ? "openai" : provider.trim().toLowerCase();
        return switch (p) {
            case "deepseek" -> "https://api.deepseek.com/chat/completions";
            case "grok", "xai" -> "https://api.x.ai/v1/chat/completions";
            case "mistral" -> "https://api.mistral.ai/v1/chat/completions";
            default -> "https://api.openai.com/v1/chat/completions"; // openai / chatgpt / gpt
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String prompt, String context) {
        String apiKey = aiSettings.effectiveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "API key is not configured; set it in the dashboard AI settings.");
        }
        String provider = aiSettings.get() != null ? aiSettings.get().provider() : "openai";
        String model = aiSettings.effectiveModel();
        String endpoint = endpointFor(provider);

        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system",
                                "content", "You are a trading assistant. Respond with STRICT JSON only."),
                        Map.of("role", "user", "content", prompt)
                )
        );

        try {
            Map<String, Object> resp = webClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (resp == null) {
                throw new IllegalStateException("Empty response from " + provider);
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("No choices returned by " + provider);
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return String.valueOf(message.get("content"));
        } catch (WebClientResponseException e) {
            String msg = provider + " API error: " + e.getStatusCode() + " " + safeBody(e);
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

