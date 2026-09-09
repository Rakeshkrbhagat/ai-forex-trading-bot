package com.forexbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the Google Gemini REST client.
 * The API key must be supplied via the {@code GEMINI_API_KEY} environment
 * variable and is never hardcoded in source.
 */
@ConfigurationProperties(prefix = "gemini")
public class GeminiProperties {

    /** Gemini API key, sourced from the GEMINI_API_KEY environment variable. */
    private String apiKey;

    /** Base URL of the Gemini REST API. */
    private String baseUrl = "https://generativelanguage.googleapis.com";

    /** Model identifier used for generateContent calls. */
    private String model = "gemini-1.5-flash";

    /** Request timeout in seconds. */
    private int timeoutSeconds = 30;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}

