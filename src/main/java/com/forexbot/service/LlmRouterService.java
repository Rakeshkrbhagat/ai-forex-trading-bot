package com.forexbot.service;

import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.MarketTickRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes LLM requests to the correct provider transport (Gemini, OpenAI/ChatGPT,
 * Claude, DeepSeek, Grok, Mistral, …) based on the provider selected at runtime
 * in the dashboard AI settings. Builds a provider-agnostic prompt via
 * {@link PromptFactory} so every model is asked the same question.
 *
 * <p>Includes a lightweight rate-limit circuit breaker: when a provider returns
 * HTTP 429 (quota / too many requests) the router enters a short cooldown and
 * fails fast for subsequent calls instead of hammering the API — which only
 * makes the quota problem worse and floods the activity console.</p>
 */
@Service
public class LlmRouterService {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterService.class);

    private final List<LlmTransport> transports;
    private final PromptFactory promptFactory;
    private final AiSettingsStore aiSettings;

    /** Cooldown window (seconds) applied after a rate-limit (429) response. */
    @Value("${ai.rate-limit-cooldown-seconds:60}")
    private long cooldownSeconds;

    /** Epoch millis until which LLM calls are short-circuited. */
    private final AtomicLong cooldownUntil = new AtomicLong(0);

    public LlmRouterService(List<LlmTransport> transports,
                            PromptFactory promptFactory,
                            AiSettingsStore aiSettings) {
        this.transports = transports;
        this.promptFactory = promptFactory;
        this.aiSettings = aiSettings;
    }

    private LlmTransport resolveTransport() {
        String provider = aiSettings.get() != null ? aiSettings.get().provider() : "gemini";
        return transports.stream()
                .filter(t -> t.supports(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unsupported AI provider '" + provider + "'. Supported: gemini, openai, "
                        + "chatgpt, claude, deepseek, grok, mistral."));
    }

    private String run(String prompt, String context) {
        String provider = aiSettings.get() != null ? aiSettings.get().provider() : "gemini";
        long now = System.currentTimeMillis();
        long until = cooldownUntil.get();
        if (now < until) {
            long remaining = (until - now) / 1000 + 1;
            throw new RateLimitedException(
                    "Provider '" + provider + "' is rate-limited (429); cooling down for ~"
                    + remaining + "s.");
        }

        LlmTransport transport = resolveTransport();
        log.debug("Routing {} to provider '{}'", context, transport.providerId());
        try {
            return transport.complete(prompt, context);
        } catch (RuntimeException ex) {
            if (isRateLimit(ex)) {
                long cooldownMs = Math.max(1, cooldownSeconds) * 1000L;
                cooldownUntil.set(System.currentTimeMillis() + cooldownMs);
                log.warn("Provider '{}' returned 429; entering {}s cooldown.",
                        transport.providerId(), cooldownSeconds);
                throw new RateLimitedException(
                        "Provider '" + provider + "' quota exceeded (429). Cooling down for "
                        + cooldownSeconds + "s. Reduce poll frequency, switch model/provider, "
                        + "or upgrade your plan.", ex);
            }
            throw ex;
        }
    }

    /** Runs the trade-decision prompt against the selected provider. */
    public String analyzeMarketData(MarketDataWindow window) {
        return run(promptFactory.tradeDecisionPrompt(window), "trade-decision analysis");
    }

    /** Runs the market-structure prompt against the selected provider. */
    public String analyzeMarketStructure(MarketTickRequest tick) {
        return run(promptFactory.marketStructurePrompt(tick), "market-structure analysis");
    }

    private static boolean isRateLimit(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("429")
                    || msg.contains("TOO_MANY_REQUESTS")
                    || msg.toLowerCase().contains("quota")
                    || msg.toLowerCase().contains("rate limit"))) {
                return true;
            }
        }
        return false;
    }

    /** Thrown when the provider is rate-limited and calls are short-circuited. */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }

        public RateLimitedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

