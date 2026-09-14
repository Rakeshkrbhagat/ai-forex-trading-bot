package com.forexbot.controller;

import com.forexbot.dto.AiSettings;
import com.forexbot.service.AiSettingsStore;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints to read and configure the runtime AI + trading settings (provider,
 * model, API key, timeframe and trading style) from the dashboard. The API key
 * is never returned in clear text — only a boolean indicating whether one is set.
 */
@RestController
@RequestMapping("/api/ai")
public class AiConfigController {

    private final AiSettingsStore store;

    public AiConfigController(AiSettingsStore store) {
        this.store = store;
    }

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> get() {
        AiSettings s = store.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", s.provider());
        body.put("model", s.model());
        body.put("timeframe", s.timeframe());
        body.put("tradingStyle", s.tradingStyle());
        body.put("apiKeySet", store.hasApiKey());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> update(@Valid @RequestBody AiSettings settings) {
        store.save(settings);
        return get();
    }
}

