package com.forexbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Dashboard-selectable strategy mode, rule-based parameters and market-condition filters.
 * mode = RULES (built-in rule engine, no API key) or AI (LLM provider).
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
        Integer minConfluence,
        // ---- market-condition filters (each switchable from UI) ----
        Boolean volumeFilter,      // bar tick-volume must be >= volumeMultiplier x 20-bar avg
        Double volumeMultiplier,
        Boolean sessionFilter,     // trade only between sessionStartUtc..sessionEndUtc (hours)
        Integer sessionStartUtc,
        Integer sessionEndUtc,
        Boolean efficiencyFilter,  // Kaufman efficiency ratio (10 bars) must be >= erMin
        Double erMin,
        Boolean trendOnly,         // ignore counter-trend votes
        Boolean vwapFilter,        // BUY only above session VWAP, SELL only below
        Boolean bodyFilter,        // signal candle body >= bodyMultiplier x 20-bar avg body
        Double bodyMultiplier,
        Boolean adaptiveTp         // shrink TP when volume is below average (slow market)
) {
    public static StrategySettings defaults(String mode, double atrSl, double rr) {
        return new StrategySettings(mode, 20, 50, 200, 14, 40.0, 70.0, 30.0, 60.0, 14, atrSl, rr, 2,
                false, 1.2, false, 7, 20, false, 0.3, false, false, false, 1.0, false);
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
                minConfluence != null ? minConfluence : base.minConfluence(),
                volumeFilter != null ? volumeFilter : base.volumeFilter(),
                volumeMultiplier != null ? volumeMultiplier : base.volumeMultiplier(),
                sessionFilter != null ? sessionFilter : base.sessionFilter(),
                sessionStartUtc != null ? sessionStartUtc : base.sessionStartUtc(),
                sessionEndUtc != null ? sessionEndUtc : base.sessionEndUtc(),
                efficiencyFilter != null ? efficiencyFilter : base.efficiencyFilter(),
                erMin != null ? erMin : base.erMin(),
                trendOnly != null ? trendOnly : base.trendOnly(),
                vwapFilter != null ? vwapFilter : base.vwapFilter(),
                bodyFilter != null ? bodyFilter : base.bodyFilter(),
                bodyMultiplier != null ? bodyMultiplier : base.bodyMultiplier(),
                adaptiveTp != null ? adaptiveTp : base.adaptiveTp());
    }
}

