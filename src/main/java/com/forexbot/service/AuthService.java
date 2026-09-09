package com.forexbot.service;

import com.forexbot.config.AuthProperties;
import com.forexbot.dto.LoginRequest;
import com.forexbot.dto.LoginResponse;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless-credential authentication with an in-memory, thread-safe bearer
 * token store. Credentials are validated against {@link AuthProperties}
 * (environment-secured) using a constant-time comparison; issued tokens are
 * opaque, random, and expire after a configurable TTL.
 */
@Service
public class AuthService {

    /** Thrown when supplied credentials do not match the configured values. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException(String message) {
            super(message);
        }
    }

    private final AuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /** token -> session metadata (username + expiry). */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates credentials and, on success, issues a new bearer token.
     *
     * @throws InvalidCredentialsException when the credentials are invalid.
     */
    public LoginResponse login(LoginRequest request) {
        boolean userOk = constantTimeEquals(properties.getUsername(), request.username());
        boolean passOk = constantTimeEquals(properties.getPassword(), request.password());
        if (!userOk || !passOk) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        String token = generateToken();
        Instant expiresAt = Instant.now().plus(properties.getTokenTtlMinutes(), ChronoUnit.MINUTES);
        sessions.put(token, new Session(request.username(), expiresAt));
        return new LoginResponse(token, "Bearer", request.username(), expiresAt);
    }

    /**
     * Returns the username bound to a token when it is present and unexpired,
     * evicting expired tokens lazily.
     */
    public Optional<String> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(session.expiresAt())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.username());
    }

    /** Invalidates a token so it can no longer authorize requests. */
    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private record Session(String username, Instant expiresAt) {
    }
}

