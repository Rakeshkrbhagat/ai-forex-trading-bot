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

        sb.append(indicators(candles));
        return sb.toString();
    }

    /**
     * Computes human-trader indicators (EMA trend, RSI momentum, ATR volatility
     * and the recent swing high/low that act as support/resistance) so the LLM
     * can apply real strategy rules instead of guessing on raw candles.
     */
    private String indicators(List<Candle> candles) {
        List<Double> closes = new ArrayList<>();
        for (Candle c : candles) {
            closes.add(c.close());
        }
        int n = closes.size();
        if (n < 5) {
            return "\nIndicators: insufficient data.\n";
        }
        double lastClose = closes.get(n - 1);
        Double ema20 = ema(closes, 20);
        Double ema50 = ema(closes, 50);
        Double ema200 = ema(closes, 200);
        double rsi14 = rsi(closes, 14);
        double atr14 = atr(candles, 14);

        int swingBars = Math.min(n, 20);
        double swingHigh = candles.subList(n - swingBars, n).stream()
                .mapToDouble(Candle::high).max().orElse(lastClose);
        double swingLow = candles.subList(n - swingBars, n).stream()
                .mapToDouble(Candle::low).min().orElse(lastClose);

        String trend;
        if (ema20 != null && ema50 != null) {
            boolean up = ema20 > ema50 && lastClose > ema20;
            boolean down = ema20 < ema50 && lastClose < ema20;
            trend = up ? "UPTREND (EMA20>EMA50, price>EMA20)"
                    : down ? "DOWNTREND (EMA20<EMA50, price<EMA20)"
                    : "RANGE/NO-TREND (EMAs not aligned)";
        } else {
            trend = "UNKNOWN (not enough bars for EMA)";
        }

        String momentum = rsi14 >= 70 ? "OVERBOUGHT"
                : rsi14 <= 30 ? "OVERSOLD"
                : rsi14 >= 55 ? "BULLISH"
                : rsi14 <= 45 ? "BEARISH" : "NEUTRAL";

        StringBuilder sb = new StringBuilder();
        sb.append("\nIndicators (for strategy evaluation):\n");
        sb.append(String.format("  EMA20: %s  EMA50: %s  EMA200: %s%n",
                fmt(ema20), fmt(ema50), fmt(ema200)));
        sb.append(String.format("  Trend: %s%n", trend));
        sb.append(String.format("  RSI(14): %.1f (%s)%n", rsi14, momentum));
        sb.append(String.format("  ATR(14): %.5f (volatility / suggested stop distance)%n", atr14));
        sb.append(String.format("  Recent swing HIGH (resistance): %.5f%n", swingHigh));
        sb.append(String.format("  Recent swing LOW (support): %.5f%n", swingLow));
        sb.append(String.format("  Price vs swing: %.1f%% of range%n",
                swingHigh > swingLow ? (lastClose - swingLow) / (swingHigh - swingLow) * 100.0 : 50.0));
        return sb.toString();
    }

    private static String fmt(Double v) {
        return v == null ? "n/a" : String.format("%.5f", v);
    }

    /** Exponential moving average of the last {@code period} closes. */
    private Double ema(List<Double> closes, int period) {
        int n = closes.size();
        if (n < period) {
            return null;
        }
        double k = 2.0 / (period + 1);
        // Seed with the SMA of the first `period` values, then roll forward.
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += closes.get(i);
        }
        ema /= period;
        for (int i = period; i < n; i++) {
            ema = closes.get(i) * k + ema * (1 - k);
        }
        return ema;
    }

    /** Wilder's RSI over {@code period} closes; 50 when insufficient data. */
    private double rsi(List<Double> closes, int period) {
        int n = closes.size();
        if (n <= period) {
            return 50.0;
        }
        double gain = 0.0;
        double loss = 0.0;
        for (int i = n - period; i < n; i++) {
            double diff = closes.get(i) - closes.get(i - 1);
            if (diff >= 0) {
                gain += diff;
            } else {
                loss -= diff;
            }
        }
        if (loss == 0) {
            return 100.0;
        }
        double rs = (gain / period) / (loss / period);
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /** Average True Range over {@code period} candles. */
    private double atr(List<Candle> candles, int period) {
        int n = candles.size();
        if (n < 2) {
            return 0.0;
        }
        int count = Math.min(period, n - 1);
        double sum = 0.0;
        for (int i = n - count; i < n; i++) {
            Candle cur = candles.get(i);
            double prevClose = candles.get(i - 1).close();
            double tr = Math.max(cur.high() - cur.low(),
                    Math.max(Math.abs(cur.high() - prevClose), Math.abs(cur.low() - prevClose)));
            sum += tr;
        }
        return sum / count;
    }
}

