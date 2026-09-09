package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Result returned by the MT5 bridge for a dispatched order. Captures broker
 * retcodes, realized slippage and connection/rejection diagnostics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderResult(
        boolean accepted,
        String status,
        Integer brokerRetcode,
        Long orderTicket,
        Double executedPrice,
        Double executedVolume,
        Double slippagePips,
        String message,
        Instant timestamp
) {

    public static OrderResult rejected(String status, String message) {
        return new OrderResult(false, status, null, null, null, null, null,
                message, Instant.now());
    }
}

