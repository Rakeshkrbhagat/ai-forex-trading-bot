package com.forexbot.service;

/**
 * Minimal transport abstraction over an LLM provider (Gemini, OpenAI/ChatGPT,
 * Claude, DeepSeek, …). Each implementation knows how to authenticate and call
 * its provider's REST API, returning the raw text completion for a prompt.
 */
public interface LlmTransport {

    /** Lower-case provider id this transport handles, e.g. "gemini", "openai". */
    String providerId();

    /** Returns true when this transport can serve the given provider id. */
    default boolean supports(String provider) {
        return provider != null && providerId().equalsIgnoreCase(provider.trim());
    }

    /**
     * Sends the prompt to the provider and returns the raw text response
     * (expected to contain the requested strict JSON).
     */
    String complete(String prompt, String context);
}

