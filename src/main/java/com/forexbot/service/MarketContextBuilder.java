package com.forexbot.service;

import com.forexbot.dto.Candle;
import com.forexbot.dto.MarketContext;
import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.MarketTickRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Market context builder: aggregates recent MT5 price action (OHLC arrays and
 * live tick data) into a formatted prompt payload for the AI decision engine.
 */
@Service
public class MarketContextBuilder {

    private static final int RECENT_BARS = 20;

    /** Builds a full {@link MarketContext} from a candle window. */
    public MarketContext build(MarketDataWindow window) {
        return build(window, null);
    }

    /**
     * Builds a {@link MarketContext} from a candle window and an optional live
     * tick, producing a compact, deterministic prompt payload.
     */
    public MarketContext build(MarketDataWindow window, MarketTickRequest tick) {
        List<Double> opens = new ArrayList<>();
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        List<Double> closes = new ArrayList<>();

        List<Candle> candles = window.candles() != null ? window.candles() : List.of();
        for (Candle c : candles) {
            opens.add(c.open());
            highs.add(c.high());
            lows.add(c.low());
            closes.add(c.close());
        }

        String payload = format(window, candles, tick);
        return new MarketContext(
                window.symbol(),
                window.timeframe(),
                candles.size(),
                opens, highs, lows, closes,
                window.lastClose(),
                tick != null ? tick.bid() : null,
                tick != null ? tick.ask() : null,
                payload);
    }

    /** Produces just the formatted prompt payload string. */
    public String buildPromptPayload(MarketDataWindow window) {
        return build(window).promptPayload();
    }

    private String format(MarketDataWindow window, List<Candle> candles, MarketTickRequest tick) {
        if (candles.isEmpty()) {
            return "No market data available for " + window.symbol();
        }

        double hi = candles.stream().mapToDouble(Candle::high).max().orElse(0);
        double lo = candles.stream().mapToDouble(Candle::low).min().orElse(0);
        Candle first = candles.get(0);
        Candle last = candles.get(candles.size() - 1);
        double change = last.close() - first.open();
        double range = hi - lo;

        StringBuilder sb = new StringBuilder();
        sb.append("Symbol: ").append(window.symbol())
                .append(" | Timeframe: ").append(window.timeframe())
                .append(" | Bars: ").append(candles.size()).append('\n');
        sb.append(String.format(
                "WindowOpen: %.5f  WindowClose: %.5f  High: %.5f  Low: %.5f  Range: %.5f  Change: %.5f%n",
                first.open(), last.close(), hi, lo, range, change));
        if (tick != null) {
            sb.append(String.format("LiveTick -> bid: %.5f  ask: %.5f%n", tick.bid(), tick.ask()));
        }

        int start = Math.max(0, candles.size() - RECENT_BARS);
        sb.append("Recent candles (time, O, H, L, C):\n");
        for (int i = start; i < candles.size(); i++) {
            Candle c = candles.get(i);
            sb.append(String.format("%s, %.5f, %.5f, %.5f, %.5f%n",
                    c.time(), c.open(), c.high(), c.low(), c.close()));
        }
        return sb.toString();
    }
}

