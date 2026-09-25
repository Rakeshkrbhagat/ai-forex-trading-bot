package com.forexbot.service;

import com.forexbot.dto.Candle;
import com.forexbot.dto.MarketDataWindow;
import com.forexbot.dto.StrategySettings;
import com.forexbot.dto.TradeDecisionSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, API-key-free multi-strategy engine. Runs 32 rules (classic + liquidity sweep + SMC + IMC/ICT) on
 * every closed candle; each rule may vote BUY or SELL. The side with the most
 * votes wins if it reaches {@code minConfluence} votes and beats the opposite
 * side. SL = ATR x multiplier, TP = SL x reward:risk.
 */
@Service
public class RuleBasedStrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedStrategyEngine.class);

    private final StrategySettingsStore store;

    public RuleBasedStrategyEngine(StrategySettingsStore store) {
        this.store = store;
    }

    /** Pre-computed indicator series for one window. */
    private static final class Ind {
        int n;
        double[] o, h, l, c;
        double[] emaF, emaS, emaT, ema9, ema21, rsi, atr;
        double[] macd, macdSig;
        double[] bbMid, bbUp, bbLo, bbWidth;
        double[] stochK, stochD;
        double[] adx, pdi, mdi;
        double[] haO, haC;
        boolean up, down; // main trend filter
    }

    private record Vote(String side, String name) { }

    public TradeDecisionSignal decide(MarketDataWindow window) {
        StrategySettings p = store.get();
        String symbol = window != null ? window.symbol() : "unknown";
        int minBars = Math.max(60, Math.max(p.emaSlow(), Math.max(p.rsiPeriod(), p.atrPeriod())) + 5);
        if (window == null || window.isEmpty() || window.candles().size() < minBars) {
            return hold(symbol, "Not enough candles (need " + minBars + "+)");
        }
        Ind x = compute(window.candles(), p);
        int i = x.n - 1;
        double atr = x.atr[i];
        if (atr <= 0) {
            return hold(symbol, "ATR is zero");
        }

        List<Vote> votes = new ArrayList<>();
        evaluateAll(x, p, votes);

        long buys = votes.stream().filter(v -> v.side().equals("BUY")).count();
        long sells = votes.size() - buys;
        int need = Math.max(1, p.minConfluence());
        String names = String.join(", ", votes.stream().map(v -> v.side() + ":" + v.name()).toList());
        String ind = String.format("RSI=%.1f ATR=%.5f ADX=%.1f trend=%s", x.rsi[i], atr, x.adx[i],
                x.up ? "UP" : x.down ? "DOWN" : "FLAT");
        log.info("Rule engine {} -> buys={} sells={} [{}] {}", symbol, buys, sells, names, ind);

        String side = null;
        long count = 0;
        if (buys >= need && buys > sells) { side = "BUY"; count = buys; }
        else if (sells >= need && sells > buys) { side = "SELL"; count = sells; }

        if (side == null) {
            String why = votes.isEmpty() ? "No rule triggered" :
                    "Not enough agreement (buy " + buys + " / sell " + sells + ", need " + need + ")";
            return hold(symbol, why + ". " + ind + (names.isEmpty() ? "" : " [" + names + "]"));
        }

        double entry = x.c[i];
        double slDist = atr * p.atrSlMultiplier();
        boolean buy = side.equals("BUY");
        double sl = buy ? entry - slDist : entry + slDist;
        double tp = buy ? entry + slDist * p.rewardRisk() : entry - slDist * p.rewardRisk();
        double conf = Math.min(0.95, 0.5 + 0.1 * count);
        String label = votes.stream().filter(v -> v.side().equals(buy ? "BUY" : "SELL"))
                .map(Vote::name).reduce((a, b) -> a + " + " + b).orElse("Rules");
        return new TradeDecisionSignal(symbol, side, 0.0, sl, tp, conf, label,
                count + " rule(s) agree: " + label + ". " + ind);
    }

    // ------------------------------------------------------------------ rules

    private void evaluateAll(Ind x, StrategySettings p, List<Vote> v) {
        int i = x.n - 1, j = i - 1;
        double[] c = x.c, o = x.o, h = x.h, l = x.l;
        double body = Math.abs(c[i] - o[i]);
        double range = Math.max(1e-12, h[i] - l[i]);
        boolean bull = c[i] > o[i], bear = c[i] < o[i];

        // 1. Trend pullback to fast EMA
        if (x.up && l[j] <= x.emaF[j] && c[i] > x.emaF[i] && bull
                && x.rsi[i] > p.rsiBuyMin() && x.rsi[i] < p.rsiBuyMax()) add(v, "BUY", "Trend pullback");
        if (x.down && h[j] >= x.emaF[j] && c[i] < x.emaF[i] && bear
                && x.rsi[i] > p.rsiSellMin() && x.rsi[i] < p.rsiSellMax()) add(v, "SELL", "Trend pullback");

        // 2. Fast/slow EMA crossover
        if (x.emaF[j] <= x.emaS[j] && x.emaF[i] > x.emaS[i]) add(v, "BUY", "EMA crossover");
        if (x.emaF[j] >= x.emaS[j] && x.emaF[i] < x.emaS[i]) add(v, "SELL", "EMA crossover");

        // 3. EMA 9/21 crossover
        if (x.ema9[j] <= x.ema21[j] && x.ema9[i] > x.ema21[i]) add(v, "BUY", "EMA 9/21 cross");
        if (x.ema9[j] >= x.ema21[j] && x.ema9[i] < x.ema21[i]) add(v, "SELL", "EMA 9/21 cross");

        // 4. MACD signal-line cross
        if (x.macd[j] <= x.macdSig[j] && x.macd[i] > x.macdSig[i]) add(v, "BUY", "MACD cross");
        if (x.macd[j] >= x.macdSig[j] && x.macd[i] < x.macdSig[i]) add(v, "SELL", "MACD cross");

        // 5. MACD zero-line cross
        if (x.macd[j] <= 0 && x.macd[i] > 0) add(v, "BUY", "MACD zero cross");
        if (x.macd[j] >= 0 && x.macd[i] < 0) add(v, "SELL", "MACD zero cross");

        // 6. RSI oversold/overbought exit
        if (x.rsi[j] < 30 && x.rsi[i] >= 30) add(v, "BUY", "RSI oversold exit");
        if (x.rsi[j] > 70 && x.rsi[i] <= 70) add(v, "SELL", "RSI overbought exit");

        // 7. RSI 50 cross with trend
        if (x.up && x.rsi[j] < 50 && x.rsi[i] >= 50) add(v, "BUY", "RSI 50 cross");
        if (x.down && x.rsi[j] > 50 && x.rsi[i] <= 50) add(v, "SELL", "RSI 50 cross");

        // 8. Bollinger band bounce
        if (c[j] < x.bbLo[j] && c[i] > x.bbLo[i] && bull) add(v, "BUY", "Bollinger bounce");
        if (c[j] > x.bbUp[j] && c[i] < x.bbUp[i] && bear) add(v, "SELL", "Bollinger bounce");

        // 9. Bollinger breakout with trend
        if (x.up && c[j] <= x.bbUp[j] && c[i] > x.bbUp[i]) add(v, "BUY", "Bollinger breakout");
        if (x.down && c[j] >= x.bbLo[j] && c[i] < x.bbLo[i]) add(v, "SELL", "Bollinger breakout");

        // 10. Bollinger squeeze breakout
        double minW = Double.MAX_VALUE;
        for (int k = i - 20; k < i; k++) minW = Math.min(minW, x.bbWidth[k]);
        if (x.bbWidth[j] <= minW * 1.1) {
            if (c[i] > x.bbUp[i]) add(v, "BUY", "Squeeze breakout");
            if (c[i] < x.bbLo[i]) add(v, "SELL", "Squeeze breakout");
        }

        // 11. Donchian 20 breakout
        double hh = max(h, i - 20, i - 1), ll = min(l, i - 20, i - 1);
        if (c[i] > hh) add(v, "BUY", "Donchian breakout");
        if (c[i] < ll) add(v, "SELL", "Donchian breakout");

        // 12. Stochastic cross in extreme zone
        if (x.stochK[j] <= x.stochD[j] && x.stochK[i] > x.stochD[i] && x.stochK[i] < 25) add(v, "BUY", "Stochastic cross");
        if (x.stochK[j] >= x.stochD[j] && x.stochK[i] < x.stochD[i] && x.stochK[i] > 75) add(v, "SELL", "Stochastic cross");

        // 13. Engulfing candle
        if (bull && c[j] < o[j] && c[i] > o[j] && o[i] < c[j]) add(v, "BUY", "Bullish engulfing");
        if (bear && c[j] > o[j] && c[i] < o[j] && o[i] > c[j]) add(v, "SELL", "Bearish engulfing");

        // 14. Pin bar (hammer / shooting star)
        double lowerWick = Math.min(o[i], c[i]) - l[i];
        double upperWick = h[i] - Math.max(o[i], c[i]);
        if (lowerWick > 2 * body && lowerWick > 0.6 * range && l[i] <= min(l, i - 10, i - 1)) add(v, "BUY", "Hammer");
        if (upperWick > 2 * body && upperWick > 0.6 * range && h[i] >= max(h, i - 10, i - 1)) add(v, "SELL", "Shooting star");

        // 15. Inside-bar breakout (bar j-1 mother, j inside, i breaks)
        int m = i - 2;
        if (h[j] < h[m] && l[j] > l[m]) {
            if (c[i] > h[m]) add(v, "BUY", "Inside bar breakout");
            if (c[i] < l[m]) add(v, "SELL", "Inside bar breakout");
        }

        // 16. Three soldiers / three crows
        if (c[i] > o[i] && c[j] > o[j] && c[m] > o[m] && c[i] > c[j] && c[j] > c[m]) add(v, "BUY", "Three soldiers");
        if (c[i] < o[i] && c[j] < o[j] && c[m] < o[m] && c[i] < c[j] && c[j] < c[m]) add(v, "SELL", "Three crows");

        // 17. Price crosses trend EMA
        if (c[j] <= x.emaT[j] && c[i] > x.emaT[i]) add(v, "BUY", "Trend EMA cross");
        if (c[j] >= x.emaT[j] && c[i] < x.emaT[i]) add(v, "SELL", "Trend EMA cross");

        // 18. ADX strong trend + DI cross
        if (x.adx[i] > 20 && x.pdi[j] <= x.mdi[j] && x.pdi[i] > x.mdi[i]) add(v, "BUY", "ADX DI cross");
        if (x.adx[i] > 20 && x.pdi[j] >= x.mdi[j] && x.pdi[i] < x.mdi[i]) add(v, "SELL", "ADX DI cross");

        // 19. Momentum (ROC 10) zero cross with trend
        double roc = c[i] - c[i - 10], rocPrev = c[j] - c[j - 10];
        if (x.up && rocPrev <= 0 && roc > 0) add(v, "BUY", "Momentum cross");
        if (x.down && rocPrev >= 0 && roc < 0) add(v, "SELL", "Momentum cross");

        // 20. Keltner channel breakout (EMA20 +/- 2 ATR)
        double kUp = x.ema21[i] + 2 * x.atr[i], kLo = x.ema21[i] - 2 * x.atr[i];
        if (x.up && c[i] > kUp) add(v, "BUY", "Keltner breakout");
        if (x.down && c[i] < kLo) add(v, "SELL", "Keltner breakout");

        // 21. Support / resistance bounce (swing of last 30 bars)
        double sup = min(l, i - 30, i - 3), res = max(h, i - 30, i - 3);
        double tol = x.atr[i] * 0.3;
        if (l[i] <= sup + tol && c[i] > sup && bull) add(v, "BUY", "Support bounce");
        if (h[i] >= res - tol && c[i] < res && bear) add(v, "SELL", "Resistance rejection");

        // 22. Breakout retest (broke 20-bar high 1-5 bars ago, retested, holding)
        double prevHH = max(h, i - 25, i - 6), prevLL = min(l, i - 25, i - 6);
        if (max(c, i - 5, i - 1) > prevHH && l[i] <= prevHH + tol && c[i] > prevHH && bull) add(v, "BUY", "Breakout retest");
        if (min(c, i - 5, i - 1) < prevLL && h[i] >= prevLL - tol && c[i] < prevLL && bear) add(v, "SELL", "Breakdown retest");

        // 23. RSI divergence (last 15 bars)
        int lo1 = argMin(l, i - 15, i - 5), hi1 = argMax(h, i - 15, i - 5);
        if (l[i] < l[lo1] && x.rsi[i] > x.rsi[lo1] && x.rsi[i] < 45 && bull) add(v, "BUY", "Bullish RSI divergence");
        if (h[i] > h[hi1] && x.rsi[i] < x.rsi[hi1] && x.rsi[i] > 55 && bear) add(v, "SELL", "Bearish RSI divergence");

        // 24. Heikin-Ashi colour flip with trend
        boolean haBullNow = x.haC[i] > x.haO[i], haBullPrev = x.haC[j] > x.haO[j];
        if (x.up && haBullNow && !haBullPrev) add(v, "BUY", "Heikin-Ashi flip");
        if (x.down && !haBullNow && haBullPrev) add(v, "SELL", "Heikin-Ashi flip");

        // 25. Volatility expansion candle with trend
        if (range > 1.8 * x.atr[j]) {
            if (x.up && bull && (c[i] - l[i]) > 0.75 * range) add(v, "BUY", "Volatility expansion");
            if (x.down && bear && (h[i] - c[i]) > 0.75 * range) add(v, "SELL", "Volatility expansion");
        }

        // ================= Liquidity / SMC / IMC (institutional) concepts =================
        int swingLookback = 20;
        int sHi = lastSwingHigh(h, i - 1, swingLookback);
        int sLo = lastSwingLow(l, i - 1, swingLookback);

        // 26. Liquidity sweep: wick takes out the last swing low/high (stop hunt)
        //     and the candle closes back inside the range.
        if (sLo >= 0 && l[i] < l[sLo] && c[i] > l[sLo] && bull) add(v, "BUY", "Liquidity sweep (sell-side)");
        if (sHi >= 0 && h[i] > h[sHi] && c[i] < h[sHi] && bear) add(v, "SELL", "Liquidity sweep (buy-side)");

        // 27. Equal highs / equal lows sweep (resting liquidity pool)
        int eqLo = equalLevel(l, i - 1, 30, tol, false);
        int eqHi = equalLevel(h, i - 1, 30, tol, true);
        if (eqLo >= 0 && l[i] < l[eqLo] - tol * 0.2 && c[i] > l[eqLo]) add(v, "BUY", "Equal lows sweep");
        if (eqHi >= 0 && h[i] > h[eqHi] + tol * 0.2 && c[i] < h[eqHi]) add(v, "SELL", "Equal highs sweep");

        // 28. SMC Break of Structure (BOS): close beyond last swing in trend direction
        if (x.up && sHi >= 0 && c[j] <= h[sHi] && c[i] > h[sHi]) add(v, "BUY", "SMC BOS");
        if (x.down && sLo >= 0 && c[j] >= l[sLo] && c[i] < l[sLo]) add(v, "SELL", "SMC BOS");

        // 29. SMC Change of Character (CHoCH): break of structure AGAINST prior trend
        boolean wasDown = x.emaF[i - 5] < x.emaS[i - 5], wasUp = x.emaF[i - 5] > x.emaS[i - 5];
        if (wasDown && sHi >= 0 && c[j] <= h[sHi] && c[i] > h[sHi]) add(v, "BUY", "SMC CHoCH");
        if (wasUp && sLo >= 0 && c[j] >= l[sLo] && c[i] < l[sLo]) add(v, "SELL", "SMC CHoCH");

        // 30. SMC Order Block retest: last opposite candle before an impulsive move
        //     (> 1.5 ATR over 3 bars), price returns into it and rejects.
        for (int k = i - 20; k < i - 3; k++) {
            if (k < 1) continue;
            double impulseUp = c[k + 3] - c[k], impulseDn = c[k] - c[k + 3];
            if (c[k] < o[k] && impulseUp > 1.5 * x.atr[k] && l[i] <= h[k] && c[i] > h[k] && bull && l[i] >= l[k]) {
                add(v, "BUY", "SMC order block"); break;
            }
            if (c[k] > o[k] && impulseDn > 1.5 * x.atr[k] && h[i] >= l[k] && c[i] < l[k] && bear && h[i] <= h[k]) {
                add(v, "SELL", "SMC order block"); break;
            }
        }

        // 31. SMC Fair Value Gap (FVG) fill: 3-candle imbalance, price revisits gap and rejects
        for (int k = i - 15; k < i - 2; k++) {
            if (k < 1) continue;
            boolean bullFvg = l[k + 1] > h[k - 1];   // gap up between candle k-1 high and k+1 low
            boolean bearFvg = h[k + 1] < l[k - 1];   // gap down
            if (bullFvg && x.up && l[i] <= l[k + 1] && l[i] >= h[k - 1] && bull) { add(v, "BUY", "SMC FVG fill"); break; }
            if (bearFvg && x.down && h[i] >= h[k + 1] && h[i] <= l[k - 1] && bear) { add(v, "SELL", "SMC FVG fill"); break; }
        }

        // 32. IMC / ICT Optimal Trade Entry: premium/discount of the last 40-bar dealing
        //     range; buy in discount at the 62-79% retracement in an uptrend, sell in premium.
        int rHiIdx = argMax(h, i - 40, i - 1), rLoIdx = argMin(l, i - 40, i - 1);
        double rHi = h[rHiIdx], rLo = l[rLoIdx], rSize = rHi - rLo;
        if (rSize > 2 * x.atr[i]) {
            if (x.up && rLoIdx < rHiIdx) {
                double ote1 = rHi - 0.62 * rSize, ote2 = rHi - 0.79 * rSize;
                if (l[i] <= ote1 && l[i] >= ote2 && bull) add(v, "BUY", "IMC OTE discount");
            }
            if (x.down && rHiIdx < rLoIdx) {
                double ote1 = rLo + 0.62 * rSize, ote2 = rLo + 0.79 * rSize;
                if (h[i] >= ote1 && h[i] <= ote2 && bear) add(v, "SELL", "IMC OTE premium");
            }
        }
    }

    /** Index of the most recent 2-left/2-right fractal swing high within lookback, or -1. */
    private static int lastSwingHigh(double[] h, int end, int lookback) {
        for (int k = end - 2; k >= Math.max(2, end - lookback); k--) {
            if (h[k] > h[k - 1] && h[k] > h[k - 2] && h[k] >= h[k + 1] && h[k] >= h[k + 2]) return k;
        }
        return -1;
    }

    /** Index of the most recent 2-left/2-right fractal swing low within lookback, or -1. */
    private static int lastSwingLow(double[] l, int end, int lookback) {
        for (int k = end - 2; k >= Math.max(2, end - lookback); k--) {
            if (l[k] < l[k - 1] && l[k] < l[k - 2] && l[k] <= l[k + 1] && l[k] <= l[k + 2]) return k;
        }
        return -1;
    }

    /** Finds two swing points within tol of each other (equal highs/lows); returns the later index or -1. */
    private static int equalLevel(double[] a, int end, int lookback, double tol, boolean highs) {
        int from = Math.max(2, end - lookback);
        List<Integer> sw = new ArrayList<>();
        for (int k = end - 2; k >= from; k--) {
            boolean isSwing = highs
                    ? a[k] > a[k - 1] && a[k] > a[k - 2] && a[k] >= a[k + 1] && a[k] >= a[k + 2]
                    : a[k] < a[k - 1] && a[k] < a[k - 2] && a[k] <= a[k + 1] && a[k] <= a[k + 2];
            if (isSwing) sw.add(k);
        }
        for (int p1 = 0; p1 < sw.size(); p1++) {
            for (int p2 = p1 + 1; p2 < sw.size(); p2++) {
                if (Math.abs(a[sw.get(p1)] - a[sw.get(p2)]) <= tol) return sw.get(p1);
            }
        }
        return -1;
    }

    private static void add(List<Vote> v, String side, String name) {
        v.add(new Vote(side, name));
    }

    // ------------------------------------------------------------- indicators

    private static Ind compute(List<Candle> candles, StrategySettings p) {
        Ind x = new Ind();
        int n = candles.size();
        x.n = n;
        x.o = new double[n]; x.h = new double[n]; x.l = new double[n]; x.c = new double[n];
        for (int k = 0; k < n; k++) {
            Candle cd = candles.get(k);
            x.o[k] = cd.open(); x.h[k] = cd.high(); x.l[k] = cd.low(); x.c[k] = cd.close();
        }
        x.emaF = ema(x.c, p.emaFast());
        x.emaS = ema(x.c, p.emaSlow());
        x.emaT = ema(x.c, p.emaTrend());
        x.ema9 = ema(x.c, 9);
        x.ema21 = ema(x.c, 21);
        x.rsi = rsi(x.c, p.rsiPeriod());
        x.atr = atr(x, p.atrPeriod());

        double[] e12 = ema(x.c, 12), e26 = ema(x.c, 26);
        x.macd = new double[n];
        for (int k = 0; k < n; k++) x.macd[k] = e12[k] - e26[k];
        x.macdSig = ema(x.macd, 9);

        x.bbMid = new double[n]; x.bbUp = new double[n]; x.bbLo = new double[n]; x.bbWidth = new double[n];
        for (int k = 0; k < n; k++) {
            int s = Math.max(0, k - 19);
            double sum = 0, sq = 0; int cnt = k - s + 1;
            for (int q = s; q <= k; q++) { sum += x.c[q]; sq += x.c[q] * x.c[q]; }
            double mean = sum / cnt, sd = Math.sqrt(Math.max(0, sq / cnt - mean * mean));
            x.bbMid[k] = mean; x.bbUp[k] = mean + 2 * sd; x.bbLo[k] = mean - 2 * sd;
            x.bbWidth[k] = mean == 0 ? 0 : (4 * sd) / mean;
        }

        x.stochK = new double[n]; x.stochD = new double[n];
        for (int k = 0; k < n; k++) {
            int s = Math.max(0, k - 13);
            double hh = max(x.h, s, k), ll = min(x.l, s, k);
            x.stochK[k] = hh == ll ? 50 : (x.c[k] - ll) / (hh - ll) * 100;
            int s3 = Math.max(0, k - 2); double sum = 0;
            for (int q = s3; q <= k; q++) sum += x.stochK[q];
            x.stochD[k] = sum / (k - s3 + 1);
        }

        adx(x, 14);

        x.haO = new double[n]; x.haC = new double[n];
        for (int k = 0; k < n; k++) {
            x.haC[k] = (x.o[k] + x.h[k] + x.l[k] + x.c[k]) / 4;
            x.haO[k] = k == 0 ? (x.o[k] + x.c[k]) / 2 : (x.haO[k - 1] + x.haC[k - 1]) / 2;
        }

        int i = n - 1;
        boolean trendOk = n >= p.emaTrend();
        x.up = x.emaF[i] > x.emaS[i] && (!trendOk || x.emaS[i] > x.emaT[i]);
        x.down = x.emaF[i] < x.emaS[i] && (!trendOk || x.emaS[i] < x.emaT[i]);
        return x;
    }

    private static double[] ema(double[] v, int period) {
        double[] out = new double[v.length];
        double k = 2.0 / (period + 1);
        out[0] = v[0];
        for (int i = 1; i < v.length; i++) out[i] = v[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    private static double[] rsi(double[] v, int period) {
        double[] out = new double[v.length];
        java.util.Arrays.fill(out, 50);
        if (v.length <= period) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = v[i] - v[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        gain /= period; loss /= period;
        out[period] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss);
        for (int i = period + 1; i < v.length; i++) {
            double d = v[i] - v[i - 1];
            gain = (gain * (period - 1) + Math.max(d, 0)) / period;
            loss = (loss * (period - 1) + Math.max(-d, 0)) / period;
            out[i] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss);
        }
        return out;
    }

    private static double[] atr(Ind x, int period) {
        int n = x.n;
        double[] out = new double[n];
        double a = x.h[0] - x.l[0];
        out[0] = a;
        for (int i = 1; i < n; i++) {
            double tr = Math.max(x.h[i] - x.l[i],
                    Math.max(Math.abs(x.h[i] - x.c[i - 1]), Math.abs(x.l[i] - x.c[i - 1])));
            a = (a * (period - 1) + tr) / period;
            out[i] = a;
        }
        return out;
    }

    private static void adx(Ind x, int period) {
        int n = x.n;
        x.adx = new double[n]; x.pdi = new double[n]; x.mdi = new double[n];
        double trS = 0, pS = 0, mS = 0, adx = 0;
        for (int i = 1; i < n; i++) {
            double upMove = x.h[i] - x.h[i - 1], downMove = x.l[i - 1] - x.l[i];
            double pdm = upMove > downMove && upMove > 0 ? upMove : 0;
            double mdm = downMove > upMove && downMove > 0 ? downMove : 0;
            double tr = Math.max(x.h[i] - x.l[i],
                    Math.max(Math.abs(x.h[i] - x.c[i - 1]), Math.abs(x.l[i] - x.c[i - 1])));
            trS = trS - trS / period + tr;
            pS = pS - pS / period + pdm;
            mS = mS - mS / period + mdm;
            double pdi = trS == 0 ? 0 : 100 * pS / trS, mdi = trS == 0 ? 0 : 100 * mS / trS;
            double dx = (pdi + mdi) == 0 ? 0 : 100 * Math.abs(pdi - mdi) / (pdi + mdi);
            adx = (adx * (period - 1) + dx) / period;
            x.pdi[i] = pdi; x.mdi[i] = mdi; x.adx[i] = adx;
        }
    }

    private static double max(double[] a, int from, int to) {
        double m = -Double.MAX_VALUE;
        for (int k = Math.max(0, from); k <= to; k++) m = Math.max(m, a[k]);
        return m;
    }

    private static double min(double[] a, int from, int to) {
        double m = Double.MAX_VALUE;
        for (int k = Math.max(0, from); k <= to; k++) m = Math.min(m, a[k]);
        return m;
    }

    private static int argMin(double[] a, int from, int to) {
        int best = Math.max(0, from);
        for (int k = best; k <= to; k++) if (a[k] < a[best]) best = k;
        return best;
    }

    private static int argMax(double[] a, int from, int to) {
        int best = Math.max(0, from);
        for (int k = best; k <= to; k++) if (a[k] > a[best]) best = k;
        return best;
    }

    private static TradeDecisionSignal hold(String symbol, String why) {
        return new TradeDecisionSignal(symbol, "HOLD", 0.0, 0.0, 0.0, 0.0, "None", why);
    }
}

