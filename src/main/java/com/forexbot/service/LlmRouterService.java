package com.forexbot.service;

import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.MarketTickRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Routes LLM requests to the correct provider transport (Gemini, OpenAI/ChatGPT,
 * Claude, DeepSeek, Grok, Mistral, …) based on the provider selected at runtime
 * in the dashboard AI settings. Builds a provider-agnostic prompt via
 * {@link PromptFactory} so every model is asked the same question.
 */
@Service
public class LlmRouterService {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterService.class);

    private final List<LlmTransport> transports;
    private final PromptFactory promptFactory;
    private final AiSettingsStore aiSettings;

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

    /** Runs the trade-decision prompt against the selected provider. */
    public String analyzeMarketData(MarketDataWindow window) {
        LlmTransport transport = resolveTransport();
        log.debug("Routing trade-decision analysis to provider '{}'", transport.providerId());
        return transport.complete(promptFactory.tradeDecisionPrompt(window), "trade-decision analysis");
    }

    /** Runs the market-structure prompt against the selected provider. */
    public String analyzeMarketStructure(MarketTickRequest tick) {
        LlmTransport transport = resolveTransport();
        log.debug("Routing market-structure analysis to provider '{}'", transport.providerId());
        return transport.complete(promptFactory.marketStructurePrompt(tick), "market-structure analysis");
    }
}

