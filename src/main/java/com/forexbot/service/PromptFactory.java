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
            case "SCALPING" -> "Trade as a SCALPER: target very short-term moves, tight stops and "
                    + "quick take-profits; favour high-probability momentum bursts.";
            case "SWING" -> "Trade as a SWING trader: hold for larger multi-session moves, wider "
                    + "stops/targets aligned with the dominant trend and key levels.";
            default -> "Trade as an INTRADAY trader: capture moves within the session, balancing "
                    + "reward vs. risk and closing exposure intraday.";
        };
        return """
                You are an autonomous forex trading agent and risk-aware strategist.
                Trading style: %s
                %s
                Analyze the recent price action and respond with STRICT JSON only,
                no markdown, no commentary. Use this exact schema:
                {
                  "symbol": string,
                  "action": "BUY" | "SELL" | "HOLD",
                  "volume": number,            // order size in lots, e.g. 0.10
                  "sl": number,                // stop-loss price
                  "tp": number,                // take-profit price
                  "confidenceScore": number    // 0.0 - 1.0
                }
                Rules:
                - Only signal BUY or SELL on a clear, high-conviction setup; otherwise HOLD.
                - For HOLD, set volume, sl and tp to 0.
                - confidenceScore reflects conviction from 0.0 (none) to 1.0 (certain).
                - Align stop-loss / take-profit distances with the stated trading style.

                Market data context:
                %s
                """.formatted(style, styleGuidance, contextBuilder.buildPromptPayload(window));
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

