package com.forexbot.service;

import com.forexbot.dto.RiskGuardrails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the user-defined {@link RiskGuardrails} that bound the
 * autonomous agent. Seeded with defaults until the dashboard configures them.
 */
@Service
public class RiskGuardrailStore {

    private static final Logger log = LoggerFactory.getLogger(RiskGuardrailStore.class);

    private final AtomicReference<RiskGuardrails> current =
            new AtomicReference<>(RiskGuardrails.defaults());

    public void save(RiskGuardrails guardrails) {
        current.set(guardrails);
        log.info("Risk guardrails updated: maxRisk={}% symbols={} maxDrawdown={} autonomous={}",
                guardrails.maxRiskPercent(), guardrails.allowedSymbols(),
                guardrails.maxDrawdownUsd(), guardrails.autonomousEnabled());
    }

    public RiskGuardrails get() {
        return current.get();
    }
}

