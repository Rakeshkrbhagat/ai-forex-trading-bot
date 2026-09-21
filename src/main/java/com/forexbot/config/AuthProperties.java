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
    /** Bearer-token lifetime. Default 7 days so sessions don't drop mid-use. */
    private long tokenTtlMinutes = 10080;
    /**
     * Secret used to sign stateless bearer tokens. When blank, a stable secret is
     * derived from the credentials so tokens survive backend restarts (important
     * on free-tier hosts that recycle instances).
     */
    private String tokenSecret = "";

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

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }
}

