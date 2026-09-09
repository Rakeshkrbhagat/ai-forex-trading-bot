package com.forexbot.service;

import com.forexbot.dto.RiskGuardrails;
import com.forexbot.dto.TradeDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validation layer that intercepts LLM-generated trade signals and cross-checks
 * them against the user's {@link RiskGuardrails} before any order is dispatched.
 * Also derives position size (lots) from the configured risk percentage.
 */
@Service
public class TradeSignalValidator {

    private static final Logger log = LoggerFactory.getLogger(TradeSignalValidator.class);

    /** USD value of one pip for a 1.0 standard lot (approximation for FX majors). */
    private static final double PIP_VALUE_PER_LOT = 10.0;
    private static final double MIN_LOT = 0.01;
    private static final double MAX_LOT = 100.0;

    private final RiskGuardrailStore guardrailStore;
    private final BotStateManager stateManager;

    public TradeSignalValidator(RiskGuardrailStore guardrailStore, BotStateManager stateManager) {
        this.guardrailStore = guardrailStore;
        this.stateManager = stateManager;
    }

    /**
     * Validates an AI trade signal against the active guardrails.
     *
     * @return an {@link ValidationResult} describing whether the signal may
     *         proceed and, if so, the risk-derived volume in lots.
     */
    public ValidationResult validate(TradeDecision decision) {
        RiskGuardrails guardrails = guardrailStore.get();

        // 1) Symbol allow-list.
        if (!guardrails.permits(decision.currencyPair())) {
            String reason = "Symbol " + decision.currencyPair() + " not in allowed list "
                    + guardrails.allowedSymbols();
            log.warn("Signal rejected by guardrails: {}", reason);
            return ValidationResult.rejected(reason);
        }

        // 2) Drawdown guardrail.
        double drawdown = -Math.min(0.0, stateManager.getDailyPnlUsd());
        if (drawdown >= Math.abs(guardrails.maxDrawdownUsd())) {
            String reason = "Max drawdown reached: " + drawdown + " >= " + guardrails.maxDrawdownUsd();
            log.warn("Signal rejected by guardrails: {}", reason);
            return ValidationResult.rejected(reason);
        }

        // 3) Risk-based position sizing.
        double volume = computeVolume(guardrails);
        log.info("Signal APPROVED for {} {} -> volume {} lots (risk {}%)",
                decision.action(), decision.currencyPair(), volume, guardrails.maxRiskPercent());
        return ValidationResult.approved(volume);
    }

    private double computeVolume(RiskGuardrails guardrails) {
        double riskAmount = guardrails.accountBalanceUsd() * (guardrails.maxRiskPercent() / 100.0);
        int slPips = Math.max(1, guardrails.stopLossPips());
        double lots = riskAmount / (slPips * PIP_VALUE_PER_LOT);
        lots = Math.max(MIN_LOT, Math.min(MAX_LOT, lots));
        // Round to 2 decimals (0.01 lot step).
        return Math.round(lots * 100.0) / 100.0;
    }

    /** Outcome of a guardrail validation. */
    public record ValidationResult(boolean approved, double volume, String reason) {
        public static ValidationResult approved(double volume) {
            return new ValidationResult(true, volume, null);
        }

        public static ValidationResult rejected(String reason) {
            return new ValidationResult(false, 0.0, reason);
        }
    }
}

