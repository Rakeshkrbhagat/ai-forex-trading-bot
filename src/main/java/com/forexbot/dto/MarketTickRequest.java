package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * Incoming market tick data used to drive trade decisions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketTickRequest(

        @NotBlank(message = "currencyPair is required")
        String currencyPair,

        @NotNull(message = "bid price is required")
        @Positive(message = "bid must be positive")
        Double bid,

        @NotNull(message = "ask price is required")
        @Positive(message = "ask must be positive")
        Double ask,

        Instant timestamp
) {
}

