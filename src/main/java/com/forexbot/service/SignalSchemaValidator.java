package com.forexbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forexbot.dto.TradeDecisionSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Strict JSON schema validator for LLM outputs. Parses the raw model text into a
 * type-safe {@link TradeDecisionSignal} and enforces business-rule sanity so
 * malformed or hallucinated payloads never reach the execution layer.
 */
@Service
public class SignalSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SignalSchemaValidator.class);

    private static final Set<String> VALID_ACTIONS = Set.of("BUY", "SELL", "HOLD");
    private static final double MIN_VOLUME = 0.01;
    private static final double MAX_VOLUME = 100.0;

    private final ObjectMapper objectMapper;

    public SignalSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Raised when the LLM output cannot be parsed/validated into the schema. */
    public static class InvalidSignalException extends RuntimeException {
        public InvalidSignalException(String message) {
            super(message);
        }
    }

    /**
     * Parses and validates raw LLM JSON into a {@link TradeDecisionSignal}.
     *
     * @throws InvalidSignalException on malformed JSON or schema violations.
     */
    public TradeDecisionSignal parseAndValidate(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new InvalidSignalException("Empty LLM response");
        }
        TradeDecisionSignal signal;
        try {
            signal = objectMapper.readValue(sanitize(rawJson), TradeDecisionSignal.class);
        } catch (Exception ex) {
            throw new InvalidSignalException("Malformed JSON: " + ex.getMessage());
        }

        String error = validate(signal);
        if (error != null) {
            throw new InvalidSignalException(error);
        }
        return signal;
    }

    /** Returns {@code null} when valid, otherwise the first violation reason. */
    public String validate(TradeDecisionSignal s) {
        if (s == null) {
            return "Null signal";
        }
        if (s.symbol() == null || s.symbol().isBlank()) {
            return "Missing symbol";
        }
        if (s.action() == null || !VALID_ACTIONS.contains(s.action().toUpperCase())) {
            return "Invalid action: " + s.action();
        }
        if (s.isActionable()) {
            if (!isPositiveFinite(s.volume()) || s.volume() < MIN_VOLUME || s.volume() > MAX_VOLUME) {
                return "Invalid volume (lots): " + s.volume();
            }
            if (!isPositiveFinite(s.sl())) {
                return "Invalid stop-loss: " + s.sl();
            }
            if (!isPositiveFinite(s.tp())) {
                return "Invalid take-profit: " + s.tp();
            }
        }
        Double c = s.confidenceScore();
        if (c != null && (c.isNaN() || c < 0.0 || c > 1.0)) {
            return "Confidence out of range [0,1]: " + c;
        }
        return null;
    }

    private boolean isPositiveFinite(Double v) {
        return v != null && !v.isNaN() && !v.isInfinite() && v > 0.0;
    }

    /** Tolerates markdown code fences around the JSON body. */
    private String sanitize(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }
}

