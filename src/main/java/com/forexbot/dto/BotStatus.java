package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Runtime status snapshot of the trading bot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotStatus(
        String accountId,
        boolean running,
        int tradesExecutedToday,
        double dailyPnlUsd,
        Instant lastUpdated
) {
}

