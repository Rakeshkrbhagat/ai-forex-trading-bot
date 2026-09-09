package com.forexbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forexbot.dto.MarketAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Parses raw Gemini JSON responses into a {@link MarketAnalysis} and applies the
 * binary market-structure filter:
 * <ul>
 *   <li>{@code SIDEWAYS} -> force {@code HOLD} (execution blocked).</li>
 *   <li>{@code TRENDING} with a valid {@code BUY}/{@code SELL} direction ->
 *       execution may proceed (subject to downstream risk checks).</li>
 * </ul>
 */
@Service
public class MarketStructureFilter {

    private static final Logger log = LoggerFactory.getLogger(MarketStructureFilter.class);

    private final ObjectMapper objectMapper;

    public MarketStructureFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses the Gemini text response (expected strict JSON) into a
     * {@link MarketAnalysis}. Tolerates markdown code fences if present.
     */
    public MarketAnalysis parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new MarketAnalysisException("Empty analysis response from Gemini");
        }
        try {
            return objectMapper.readValue(sanitize(rawJson), MarketAnalysis.class);
        } catch (Exception e) {
            log.error("Failed to parse Gemini market-structure JSON: {}", rawJson, e);
            throw new MarketAnalysisException("Invalid market-structure JSON", e);
        }
    }

    /**
     * Applies the binary filter to a parsed analysis.
     *
     * @return the {@link FilterResult} indicating whether execution may proceed
     * and the effective action.
     */
    public FilterResult apply(MarketAnalysis analysis) {
        if (analysis == null || analysis.structure() == null) {
            return FilterResult.hold("Missing market structure; defaulting to HOLD");
        }

        // SIDEWAYS -> always HOLD, block execution.
        if (analysis.structure() == MarketAnalysis.Structure.SIDEWAYS) {
            return FilterResult.hold("Market is SIDEWAYS; execution blocked");
        }

        // TRENDING requires a valid BUY/SELL direction to proceed.
        MarketAnalysis.Direction direction = analysis.direction();
        if (direction == MarketAnalysis.Direction.BUY
                || direction == MarketAnalysis.Direction.SELL) {
            return FilterResult.proceed(direction,
                    "Market is TRENDING with a valid " + direction + " signal");
        }

        return FilterResult.hold("Market TRENDING but no valid BUY/SELL direction; HOLD");
    }

    /** Convenience: parse then filter in one call. */
    public FilterResult evaluate(String rawJson) {
        return apply(parse(rawJson));
    }

    private String sanitize(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            // Strip leading ```json / ``` and trailing ```
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    /** Outcome of the binary market filter. */
    public record FilterResult(
            boolean proceed,
            MarketAnalysis.Direction action,
            String reason
    ) {
        public static FilterResult hold(String reason) {
            return new FilterResult(false, MarketAnalysis.Direction.HOLD, reason);
        }

        public static FilterResult proceed(MarketAnalysis.Direction action, String reason) {
            return new FilterResult(true, action, reason);
        }
    }

    /** Raised when a Gemini analysis response cannot be parsed. */
    public static class MarketAnalysisException extends RuntimeException {
        public MarketAnalysisException(String message) {
            super(message);
        }

        public MarketAnalysisException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

