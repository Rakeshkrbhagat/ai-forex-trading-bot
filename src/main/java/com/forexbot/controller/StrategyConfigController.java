package com.forexbot.controller;

import com.forexbot.dto.StrategySettings;
import com.forexbot.service.StrategySettingsStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Read / update the strategy mode (RULES vs AI) and rule parameters. */
@RestController
@RequestMapping("/api/strategy")
public class StrategyConfigController {

    private final StrategySettingsStore store;

    public StrategyConfigController(StrategySettingsStore store) {
        this.store = store;
    }

    @GetMapping("/settings")
    public ResponseEntity<StrategySettings> get() {
        return ResponseEntity.ok(store.get());
    }

    @PostMapping("/settings")
    public ResponseEntity<?> update(@RequestBody StrategySettings settings) {
        try {
            return ResponseEntity.ok(store.save(settings));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}

