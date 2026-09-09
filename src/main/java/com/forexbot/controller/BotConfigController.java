package com.forexbot.controller;

import com.forexbot.dto.BotConfig;
import com.forexbot.dto.BotStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Endpoints for configuring the bot and reporting its status.
 */
@RestController
@RequestMapping("/api/bot")
public class BotConfigController {

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("UP");
    }

    @PostMapping("/config")
    public ResponseEntity<BotStatus> configure(@Valid @RequestBody BotConfig config) {
        BotStatus status = new BotStatus(
                config.accountId(),
                false,
                0,
                0.0,
                Instant.now()
        );
        return ResponseEntity.ok(status);
    }
}

