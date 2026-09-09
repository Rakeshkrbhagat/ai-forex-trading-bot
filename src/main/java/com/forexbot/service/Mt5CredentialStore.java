package com.forexbot.service;

import com.forexbot.dto.Mt5Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory holder for the MT5 credentials supplied at runtime via
 * the dashboard. Credentials are kept only in memory (never persisted or logged
 * in clear text) and are forwarded to the local bridge on each execution.
 */
@Service
public class Mt5CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(Mt5CredentialStore.class);

    private final AtomicReference<Mt5Credentials> current = new AtomicReference<>();

    /** Stores/replaces the active credentials. */
    public void save(Mt5Credentials credentials) {
        current.set(credentials);
        log.info("MT5 credentials updated for account {} on server {}",
                credentials.login(), credentials.server());
    }

    /** Returns the active credentials, if any have been supplied. */
    public Optional<Mt5Credentials> get() {
        return Optional.ofNullable(current.get());
    }

    /** Clears any stored credentials. */
    public void clear() {
        current.set(null);
    }

    public boolean isConfigured() {
        return current.get() != null;
    }
}

