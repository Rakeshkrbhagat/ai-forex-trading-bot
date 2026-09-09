package com.forexbot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials payload submitted to {@code /api/auth/login}.
 */
public record LoginRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password
) {
}

