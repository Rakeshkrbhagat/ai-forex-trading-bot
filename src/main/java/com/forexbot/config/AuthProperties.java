package com.forexbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized credentials for the dashboard authentication layer.
 * Values are sourced from environment variables (AUTH_USERNAME /
 * AUTH_PASSWORD) and are never hardcoded in source.
 */
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private String username = "admin";
    private String password = "changeme";
    private long tokenTtlMinutes = 60;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public long getTokenTtlMinutes() {
        return tokenTtlMinutes;
    }

    public void setTokenTtlMinutes(long tokenTtlMinutes) {
        this.tokenTtlMinutes = tokenTtlMinutes;
    }
}

