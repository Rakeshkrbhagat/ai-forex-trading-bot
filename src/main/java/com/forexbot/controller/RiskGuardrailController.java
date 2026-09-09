package com.forexbot.controller;

import com.forexbot.dto.RiskGuardrails;
import com.forexbot.service.RiskGuardrailStore;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints to read and configure the autonomous agent's risk guardrails.
 */
@RestController
@RequestMapping("/api/risk")
public class RiskGuardrailController {

    private final RiskGuardrailStore store;

    public RiskGuardrailController(RiskGuardrailStore store) {
        this.store = store;
    }

    @GetMapping("/guardrails")
    public ResponseEntity<RiskGuardrails> get() {
        return ResponseEntity.ok(store.get());
    }

    @PostMapping("/guardrails")
    public ResponseEntity<RiskGuardrails> update(@Valid @RequestBody RiskGuardrails guardrails) {
        store.save(guardrails);
        return ResponseEntity.ok(store.get());
    }
}

