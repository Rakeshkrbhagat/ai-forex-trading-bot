package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Dashboard-selectable strategy mode and rule-based parameters.
 * mode = RULES (built-in 25-rule engine, no API key) or AI (LLM provider).
 * minConfluence = how many rules must agree on the same side to open a trade.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StrategySettings(
        String mode,
        Integer emaFast,
        Integer emaSlow,
        Integer emaTrend,
        Integer rsiPeriod,
        Double rsiBuyMin,
        Double rsiBuyMax,
        Double rsiSellMin,
        Double rsiSellMax,
        Integer atrPeriod,
        Double atrSlMultiplier,
        Double rewardRisk,
        Integer minConfluence
) {
    public static StrategySettings defaults(String mode, double atrSl, double rr) {
        return new StrategySettings(mode, 20, 50, 200, 14, 40.0, 70.0, 30.0, 60.0, 14, atrSl, rr, 2);
    }

    public boolean isAi() {
        return "AI".equalsIgnoreCase(mode) || "LLM".equalsIgnoreCase(mode);
    }

    /** Fill null fields from {@code base}. */
    public StrategySettings mergeOnto(StrategySettings base) {
        return new StrategySettings(
                mode != null && !mode.isBlank() ? mode.trim().toUpperCase() : base.mode(),
                emaFast != null ? emaFast : base.emaFast(),
                emaSlow != null ? emaSlow : base.emaSlow(),
                emaTrend != null ? emaTrend : base.emaTrend(),
                rsiPeriod != null ? rsiPeriod : base.rsiPeriod(),
                rsiBuyMin != null ? rsiBuyMin : base.rsiBuyMin(),
                rsiBuyMax != null ? rsiBuyMax : base.rsiBuyMax(),
                rsiSellMin != null ? rsiSellMin : base.rsiSellMin(),
                rsiSellMax != null ? rsiSellMax : base.rsiSellMax(),
                atrPeriod != null ? atrPeriod : base.atrPeriod(),
                atrSlMultiplier != null ? atrSlMultiplier : base.atrSlMultiplier(),
                rewardRisk != null ? rewardRisk : base.rewardRisk(),
                minConfluence != null ? minConfluence : base.minConfluence());
    }
}

