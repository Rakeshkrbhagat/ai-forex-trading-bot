package com.forexbot.service;

import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.MarketTickRequest;
import org.springframework.stereotype.Component;

/**
 * Central builder for the LLM prompts used across every provider (Gemini,
 * OpenAI, Claude, DeepSeek, …). Keeping the prompt text in one place means all
 * providers ask the model the exact same question and honour the dashboard's
 * trading-style selection.
 */
@Component
public class PromptFactory {

    private final MarketContextBuilder contextBuilder;
    private final AiSettingsStore aiSettings;

    public PromptFactory(MarketContextBuilder contextBuilder, AiSettingsStore aiSettings) {
        this.contextBuilder = contextBuilder;
        this.aiSettings = aiSettings;
    }

    /** Strict-JSON trade-decision prompt, tailored to the selected trading style. */
    public String tradeDecisionPrompt(MarketDataWindow window) {
        String style = aiSettings.effectiveTradingStyle();
        String styleGuidance = switch (style == null ? "INTRADAY" : style.toUpperCase()) {
            case "SCALPING" -> "Style=SCALPING: only take clean momentum/breakout bursts with tight "
                    + "stops; avoid chop and low-volatility ranges.";
            case "SWING" -> "Style=SWING: trade with the higher-timeframe trend, enter on pullbacks "
                    + "to structure/EMA, use wider stops aligned with ATR and key levels.";
            default -> "Style=INTRADAY: trade in the direction of the intraday trend, prefer "
                    + "pullback or breakout entries at key levels, close risk intraday.";
        };
        return """
                You are a DISCIPLINED, RISK-AWARE human forex trader — NOT a bot that
                trades every candle. Professional traders stay flat most of the time
                and only act on high-probability setups. When in doubt, HOLD.

                %s

                Decide using REAL, NAMED strategies and require CONFLUENCE (at least
                2-3 aligned factors) before taking a trade:
                  1. TREND: EMA20 vs EMA50 (and price vs EMA20). Trade WITH the trend.
                  2. BREAKOUT: price breaking the recent swing HIGH/LOW with momentum.
                  3. PULLBACK: in a trend, price retracing to EMA20/structure then resuming.
                  4. SUPPORT/RESISTANCE: reaction at the recent swing low/high.
                  5. MOMENTUM: RSI(14) — avoid buying overbought / selling oversold;
                     use RSI to confirm, not to fight the trend.
                  6. VOLATILITY: size stop/target from ATR (SL ~1-1.5x ATR, TP >= 1.5x SL).

                HARD RULES (act like a human):
                - DEFAULT TO HOLD. Most candles are NOT tradable — do not force a trade.
                - Only BUY/SELL when multiple factors above agree (clear confluence).
                - Do NOT trade in a RANGE/NO-TREND market unless it is a clean
                  support/resistance bounce or a confirmed breakout.
                - Never enter counter-trend without a strong reversal signal.
                - Respect risk:reward — reject setups with reward:risk < 1.5.
                - Set confidenceScore honestly. If confidence < 0.65, you MUST HOLD.

                Respond with STRICT JSON only, no markdown, no commentary:
                {
                  "symbol": string,
                  "action": "BUY" | "SELL" | "HOLD",
                  "volume": number,            // lots, e.g. 0.10 (0 for HOLD)
                  "sl": number,                // stop-loss price (0 for HOLD)
                  "tp": number,                // take-profit price (0 for HOLD)
                  "confidenceScore": number,   // 0.0 - 1.0
                  "strategy": string,          // SHORT name of the strategy that triggered,
                                               // e.g. "EMA crossover", "SMC order block",
                                               // "Resistance breakout", "Trend pullback",
                                               // "RSI reversal", "Support bounce"
                  "rationale": string          // 1-2 sentences: the confluence you used
                }
                For HOLD, set volume/sl/tp to 0, strategy to "None" and explain in
                rationale WHY there is no valid setup (e.g. "range-bound, no confluence").

                Market data + indicators:
                %s
                """.formatted(styleGuidance, contextBuilder.buildPromptPayload(window));
    }

    /** Strict-JSON market-structure analysis prompt. */
    public String marketStructurePrompt(MarketTickRequest tick) {
        return """
                You are an expert forex market-structure analyst.
                Analyze the following market tick and respond with STRICT JSON only,
                no markdown, no commentary. Use this exact schema:
                {
                  "currencyPair": string,
                  "structure": "TRENDING" | "SIDEWAYS",
                  "direction": "BUY" | "SELL" | "HOLD",
                  "keySupport": number,
                  "keyResistance": number,
                  "confidence": number,
                  "rationale": string
                }
                Rules: if the market is SIDEWAYS, direction MUST be HOLD.
                If the market is TRENDING, direction should be BUY or SELL.

                Market tick:
                - currencyPair: %s
                - bid: %s
                - ask: %s
                - timestamp: %s
                """.formatted(
                tick.currencyPair(),
                tick.bid(),
                tick.ask(),
                tick.timestamp());
    }
}

