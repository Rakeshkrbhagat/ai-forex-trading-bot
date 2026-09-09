package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * MT5 broker credentials submitted from the dashboard to {@code /api/mt5/connect}.
 * These are forwarded to the local bridge on each execution so the correct
 * prop-firm account (e.g. The5ers) is targeted dynamically.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Mt5Credentials(

        @NotNull(message = "login is required")
        Long login,

        @NotBlank(message = "password is required")
        String password,

        @NotBlank(message = "server is required")
        String server
) {
}

