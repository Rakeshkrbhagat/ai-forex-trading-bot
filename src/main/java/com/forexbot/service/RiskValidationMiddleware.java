package com.forexbot.service;

import com.forexbot.dto.RiskGuardrails;
import com.forexbot.dto.TradeDecisionSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Risk-validation middleware: a safety interception layer between the LLM
 * decision engine and order execution. Every LLM-generated trade signal is
 * evaluated against the user's active mobile-configured {@link RiskGuardrails}
 * before dispatch. Violating signals are downgraded to {@code HOLD}.
 * <p>
 * Rules enforced (in order):
 * <ol>
 *   <li>Symbol whitelist — reject unauthorized pairs/asset classes.</li>
 *   <li>Position risk % — verify against the max allowable risk per trade.</li>
 *   <li>Drawdown circuit breaker — block new entries when equity/P&amp;L drops
 *       past the configured safety limit.</li>
 * </ol>
 */
@Service
public class RiskValidationMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RiskValidationMiddleware.class);

    /** USD value of one pip for a 1.0 standard lot (FX-major approximation). */
    private static final double PIP_VALUE_PER_LOT = 10.0;

    private final RiskGuardrailStore guardrailStore;
    private final BotStateManager stateManager;

    public RiskValidationMiddleware(RiskGuardrailStore guardrailStore,
                                    BotStateManager stateManager) {
        this.guardrailStore = guardrailStore;
        this.stateManager = stateManager;
    }

    /**
     * Intercepts and validates an LLM signal against active guardrails.
     *
     * @return a {@link ValidationResult} carrying the (possibly downgraded)
     *         signal and the outcome/reason.
     */
    public ValidationResult validate(TradeDecisionSignal signal) {
        if (signal == null) {
            return ValidationResult.rejected(TradeDecisionSignal.hold("unknown"),
                    "Null signal");
        }

        String symbol = signal.symbol();

        // HOLD signals are inherently safe — nothing to execute.
        if (!signal.isActionable()) {
            return ValidationResult.approved(signal);
        }

        RiskGuardrails guardrails = guardrailStore.get();

        // 1) Symbol whitelist.
        if (!guardrails.permits(symbol)) {
            return downgrade(signal, "Symbol " + symbol + " not in allowed list "
                    + guardrails.allowedSymbols());
        }

        // 2) Drawdown circuit breaker (block new entries).
        double drawdown = -Math.min(0.0, stateManager.getDailyPnlUsd());
        double maxDrawdown = Math.abs(guardrails.maxDrawdownUsd());
        if (drawdown >= maxDrawdown) {
            return downgrade(signal, String.format(
                    "Drawdown circuit breaker: %.2f >= limit %.2f", drawdown, maxDrawdown));
        }

        // 3) Position risk % vs max allowable per trade.
        double riskPercent = computeRiskPercent(signal, guardrails);
        if (riskPercent > guardrails.maxRiskPercent()) {
            return downgrade(signal, String.format(
                    "Position risk %.2f%% exceeds max %.2f%% per trade",
                    riskPercent, guardrails.maxRiskPercent()));
        }

        log.info("Signal APPROVED for {} {} (risk {}%)",
                signal.action(), symbol, String.format("%.2f", riskPercent));
        return ValidationResult.approved(signal);
    }

    /** Estimated risk % = (volume * slPips * pipValue) / balance * 100. */
    private double computeRiskPercent(TradeDecisionSignal signal, RiskGuardrails guardrails) {
        double balance = guardrails.accountBalanceUsd() > 0 ? guardrails.accountBalanceUsd() : 1.0;
        int slPips = guardrails.stopLossPips() > 0 ? guardrails.stopLossPips() : 20;
        double volume = signal.volume() != null ? signal.volume() : 0.0;
        double riskAmount = volume * slPips * PIP_VALUE_PER_LOT;
        return riskAmount / balance * 100.0;
    }

    /** Downgrades a failing signal to HOLD and logs the rejection reason. */
    private ValidationResult downgrade(TradeDecisionSignal original, String reason) {
        log.warn("Signal REJECTED for {}: {} -> downgraded to HOLD", original.symbol(), reason);
        return ValidationResult.rejected(TradeDecisionSignal.hold(original.symbol()), reason);
    }

    /** Outcome of the risk-validation middleware. */
    public record ValidationResult(boolean approved, TradeDecisionSignal signal, String reason) {
        public static ValidationResult approved(TradeDecisionSignal signal) {
            return new ValidationResult(true, signal, null);
        }

        public static ValidationResult rejected(TradeDecisionSignal holdSignal, String reason) {
            return new ValidationResult(false, holdSignal, reason);
        }
    }
}

