package com.forexbot.dto;

import java.time.Instant;

/**
 * Successful authentication result carrying an opaque bearer token.
 */
public record LoginResponse(
        String token,
        String tokenType,
        String username,
        Instant expiresAt
) {
}

