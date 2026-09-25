package com.forexbot.service;

import com.forexbot.dto.StrategySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe holder for the dashboard-selected strategy mode + rule params. */
@Service
public class StrategySettingsStore {

    private static final Logger log = LoggerFactory.getLogger(StrategySettingsStore.class);

    private final AtomicReference<StrategySettings> current = new AtomicReference<>();

    public StrategySettingsStore(@Value("${strategy.mode:RULES}") String mode,
                                 @Value("${strategy.atr-sl-multiplier:1.5}") double atrSl,
                                 @Value("${strategy.reward-risk:2.0}") double rr) {
        String m = "LLM".equalsIgnoreCase(mode) || "AI".equalsIgnoreCase(mode) ? "AI" : "RULES";
        current.set(StrategySettings.defaults(m, atrSl, rr));
    }

    public StrategySettings get() {
        return current.get();
    }

    public StrategySettings save(StrategySettings incoming) {
        StrategySettings merged = incoming.mergeOnto(current.get());
        if (!"AI".equals(merged.mode()) && !"RULES".equals(merged.mode())) {
            throw new IllegalArgumentException("mode must be RULES or AI");
        }
        if (merged.emaFast() >= merged.emaSlow()) {
            throw new IllegalArgumentException("emaFast must be smaller than emaSlow");
        }
        if (merged.atrSlMultiplier() <= 0 || merged.rewardRisk() <= 0) {
            throw new IllegalArgumentException("ATR multiplier and reward:risk must be > 0");
        }
        current.set(merged);
        log.info("Strategy settings updated: {}", merged);
        return merged;
    }

    public boolean isAiMode() {
        return current.get().isAi();
    }
}

