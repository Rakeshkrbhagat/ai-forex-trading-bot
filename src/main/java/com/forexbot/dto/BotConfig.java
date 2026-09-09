package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Configuration payload for the trading bot. Maps incoming JSON fields securely
 * and enforces validation on risk-management parameters.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotConfig(

        @NotBlank(message = "accountId is required")
        String accountId,

        @NotBlank(message = "currencyPair is required")
        String currencyPair,

        @NotNull(message = "maxDailyTrades is required")
        @Min(value = 1, message = "maxDailyTrades must be at least 1")
        Integer maxDailyTrades,

        @NotNull(message = "maxDailyLossUsd is required")
        @Positive(message = "maxDailyLossUsd must be positive")
        Double maxDailyLossUsd,

        @NotNull(message = "riskPerTradePercent is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "riskPerTradePercent must be greater than 0")
        Double riskPerTradePercent,

        @NotNull(message = "stopLossPips is required")
        @Positive(message = "stopLossPips must be positive")
        Integer stopLossPips
) {
}

