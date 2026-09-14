package com.forexbot.service;

import com.forexbot.config.AuthProperties;
import com.forexbot.dto.LoginRequest;
import com.forexbot.dto.LoginResponse;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless-credential authentication using self-contained, HMAC-signed bearer
 * tokens. Because the token embeds its own username + expiry and is verified by
 * signature, validation requires NO server-side session storage — so tokens stay
 * valid across backend restarts / free-tier instance recycling. A small in-memory
 * denylist provides best-effort logout revocation until natural expiry.
 */
@Service
public class AuthService {

    /** Thrown when supplied credentials do not match the configured values. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException(String message) {
            super(message);
        }
    }

    private static final String HMAC_ALGO = "HmacSHA256";

    private final AuthProperties properties;

    /** Best-effort revocation set (token -> present when logged out). */
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    public AuthService(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates credentials and, on success, issues a new signed bearer token.
     *
     * @throws InvalidCredentialsException when the credentials are invalid.
     */
    public LoginResponse login(LoginRequest request) {
        boolean userOk = constantTimeEquals(properties.getUsername(), request.username());
        boolean passOk = constantTimeEquals(properties.getPassword(), request.password());
        if (!userOk || !passOk) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        Instant expiresAt = Instant.now().plus(properties.getTokenTtlMinutes(), ChronoUnit.MINUTES);
        String token = issueToken(request.username(), expiresAt);
        return new LoginResponse(token, "Bearer", request.username(), expiresAt);
    }

    /**
     * Returns the username encoded in a valid, unexpired, correctly-signed token.
     */
    public Optional<String> validate(String token) {
        if (token == null || token.isBlank() || revoked.contains(token)) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payloadB64 = token.substring(0, dot);
        String sigB64 = token.substring(dot + 1);

        // Verify signature (constant time) before trusting any payload content.
        String expectedSig = sign(payloadB64);
        if (!constantTimeEquals(expectedSig, sigB64)) {
            return Optional.empty();
        }

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        int sep = payload.lastIndexOf('|');
        if (sep <= 0) {
            return Optional.empty();
        }
        String username = payload.substring(0, sep);
        long expiryMillis;
        try {
            expiryMillis = Long.parseLong(payload.substring(sep + 1));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (Instant.now().toEpochMilli() > expiryMillis) {
            return Optional.empty();
        }
        return Optional.of(username);
    }

    /** Best-effort revoke so a logged-out token is rejected until it expires. */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            revoked.add(token);
        }
    }

    private String issueToken(String username, Instant expiresAt) {
        String payload = username + "|" + expiresAt.toEpochMilli();
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return payloadB64 + "." + sign(payloadB64);
    }

    private String sign(String payloadB64) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretBytes(), HMAC_ALGO));
            byte[] sig = mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign auth token", ex);
        }
    }

    /**
     * Signing secret. Prefers an explicit {@code auth.token-secret}; otherwise
     * derives a stable secret from the credentials so tokens remain valid across
     * restarts without extra configuration.
     */
    private byte[] secretBytes() {
        String configured = properties.getTokenSecret();
        String basis = (configured != null && !configured.isBlank())
                ? configured
                : "forexbot|" + properties.getUsername() + "|" + properties.getPassword();
        return basis.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}

