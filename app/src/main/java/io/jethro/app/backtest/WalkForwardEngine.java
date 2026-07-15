package io.jethro.app.backtest;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Position;
import io.jethro.domain.Positions;
import io.jethro.domain.Side;
import io.jethro.trading.algo.strategy.MeanReversionStrategy;
import io.jethro.trading.algo.strategy.MomentumStrategy;
import io.jethro.trading.algo.strategy.Strategy;
import io.jethro.trading.algo.strategy.TradeSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Walk-forward replay over REAL daily bars (ADR-0027 point 2, the step past sim-seed OOS):
 * roll a fit window and an evaluation window through history — parameters are chosen on
 * the FIT window (small lookback × threshold grid, best net P&L) and the strategy is then
 * measured on the UNSEEN evaluation window with those frozen parameters. What survives is
 * out-of-sample by construction: the eval windows never influenced the parameters.
 *
 * <pre>
 *   fold k:  fit  [t₀ + k·E,  t₀ + k·E + F)     → argmax over the parameter grid
 *            eval [t₀ + k·E + F,  t₀ + k·E + F + E)  → frozen params, measured P&L
 * </pre>
 *
 * Accounting matches the sim backtest: the same average-cost ledger
 * ({@link Positions#applyFill}), per-fill cost in bps of traded notional, long-only clamp,
 * order/position caps — exact decimals throughout (invariant 1). Each window starts flat
 * and liquidates nothing: end-of-window unrealized is included in the window's P&L
 * (mark-to-mark honesty, no fake exit fills). The strategy's rolling window is WARMED UP
 * on the days immediately before each window (signals ignored) so short windows aren't
 * half warm-up. "Supported" = OOS net P&L positive on a strict majority of folds.
 */
public final class WalkForwardEngine {

    private static final BookId BOOK = new BookId("WF");
    private static final int[] LOOKBACK_GRID = {12, 24, 48};
    private static final double[] THRESHOLD_GRID = {1.5, 2.5, 3.5};

    public record Config(String algo, int fitDays, int evalDays,
                         BigDecimal minSignalBps, BigDecimal targetNotional,
                         BigDecimal maxOrderNotional, BigDecimal maxPositionNotional,
                         boolean allowShort, BigDecimal costBps,
                         Map<String, BigDecimal> multipliers) {
    }

    public record FoldResult(int fold, LocalDate fitStart, LocalDate evalStart, LocalDate evalEnd,
                             int chosenLookback, double chosenThreshold,
                             BigDecimal fitPnl, BigDecimal oosPnl, int oosTrades) {
    }

    public record Result(String algo, int fitDays, int evalDays, int days,
                         List<FoldResult> folds, BigDecimal totalOosPnl,
                         int positiveFolds, boolean supported, String note) {
    }

    public Result run(Map<String, List<HistoricalBars.Bar>> bars, Config cfg) {
        // The date axis is the union of all instruments' dates (an instrument without a bar
        // on a date simply isn't observed that day — per-instrument windows handle gaps).
        TreeSet<LocalDate> axis = new TreeSet<>();
        Map<String, Map<LocalDate, BigDecimal>> closes = new LinkedHashMap<>();
        bars.forEach((id, list) -> {
            Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
            for (HistoricalBars.Bar bar : list) {
                byDay.put(bar.day(), bar.close());
                axis.add(bar.day());
            }
            closes.put(id, byDay);
        });
        List<LocalDate> dates = new ArrayList<>(axis);
        int maxLookback = LOOKBACK_GRID[LOOKBACK_GRID.length - 1];

        List<FoldResult> folds = new ArrayList<>();
        BigDecimal totalOos = BigDecimal.ZERO;
        int positive = 0;
        int fold = 0;
        for (int start = maxLookback + 1; start + cfg.fitDays() + cfg.evalDays() <= dates.size();
             start += cfg.evalDays()) {
            int fitFrom = start;
            int fitTo = start + cfg.fitDays();
            int evalTo = fitTo + cfg.evalDays();

            // Fit: pick the grid point with the best net P&L on the fit window ONLY.
            int bestLookback = LOOKBACK_GRID[0];
            double bestThreshold = THRESHOLD_GRID[0];
            BigDecimal bestFit = null;
            for (int lookback : LOOKBACK_GRID) {
                for (double threshold : THRESHOLD_GRID) {
                    BigDecimal pnl = replay(dates, closes, fitFrom, fitTo, lookback, threshold, cfg).pnl;
                    if (bestFit == null || pnl.compareTo(bestFit) > 0) {
                        bestFit = pnl;
                        bestLookback = lookback;
                        bestThreshold = threshold;
                    }
                }
            }
            // Evaluate: frozen parameters on the unseen window.
            Replay oos = replay(dates, closes, fitTo, evalTo, bestLookback, bestThreshold, cfg);
            folds.add(new FoldResult(fold++, dates.get(fitFrom), dates.get(fitTo), dates.get(evalTo - 1),
                    bestLookback, bestThreshold, scale(bestFit), scale(oos.pnl), oos.trades));
            totalOos = totalOos.add(oos.pnl);
            if (oos.pnl.signum() > 0) {
                positive++;
            }
        }
        boolean supported = !folds.isEmpty() && positive * 2 > folds.size();
        return new Result(cfg.algo(), cfg.fitDays(), cfg.evalDays(), dates.size(), folds,
                scale(totalOos), positive, supported,
                folds.isEmpty() ? "not enough history for one fold (need warm-up + fit + eval days)" : null);
    }

    private record Replay(BigDecimal pnl, int trades) {
    }

    /** Replays [from, to) with a fresh strategy/book; warm-up feeds the preceding lookback+1
     *  days so the window isn't half warm-up (signals ignored during warm-up). */
    private static Replay replay(List<LocalDate> dates, Map<String, Map<LocalDate, BigDecimal>> closes,
                                 int from, int to, int lookback, double threshold, Config cfg) {
        Strategy strategy = "mean-reversion".equals(cfg.algo())
                ? new MeanReversionStrategy(lookback, threshold, cfg.minSignalBps())
                : new MomentumStrategy(lookback, threshold, cfg.minSignalBps());
        Map<String, Position> positions = new LinkedHashMap<>();
        Map<String, BigDecimal> lastMark = new LinkedHashMap<>();
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal costs = BigDecimal.ZERO;
        int trades = 0;
        int warmupFrom = Math.max(0, from - lookback - 1);
        for (int d = warmupFrom; d < to; d++) {
            LocalDate day = dates.get(d);
            List<Strategy.Observation> obs = new ArrayList<>();
            for (var e : closes.entrySet()) {
                BigDecimal close = e.getValue().get(day);
                if (close != null) {
                    obs.add(new Strategy.Observation(e.getKey(), close, false));
                    lastMark.put(e.getKey(), close);
                }
            }
            List<TradeSignal> signals = strategy.evaluate(obs);
            if (d < from) {
                continue; // warm-up: the rolling windows fill, nothing trades
            }
            for (TradeSignal signal : signals) {
                String id = signal.instrumentId();
                BigDecimal mult = cfg.multipliers().getOrDefault(id, BigDecimal.ONE);
                Position held = positions.computeIfAbsent(id,
                        k -> Position.flat(BOOK, new InstrumentId(k)));
                BigDecimal qty = quantity(cfg, signal, mult, held);
                if (qty == null) {
                    continue;
                }
                Fill fill = new Fill("wf-" + d + "-" + id, "wf", BOOK, new InstrumentId(id),
                        signal.side(), qty, signal.price(), Instant.EPOCH);
                Positions.FillApplication applied = Positions.applyFill(held, fill, mult);
                costs = costs.add(qty.multiply(signal.price()).multiply(mult)
                        .multiply(cfg.costBps()).movePointLeft(4));
                realized = realized.add(applied.realizedPnl());
                positions.put(id, applied.position());
                trades++;
            }
        }
        BigDecimal unrealized = BigDecimal.ZERO;
        for (var e : positions.entrySet()) {
            Position p = e.getValue();
            BigDecimal mark = lastMark.get(e.getKey());
            if (!p.isFlat() && mark != null) {
                BigDecimal mult = cfg.multipliers().getOrDefault(e.getKey(), BigDecimal.ONE);
                unrealized = unrealized.add(
                        p.quantity().multiply(mark.subtract(p.avgCost())).multiply(mult));
            }
        }
        return new Replay(realized.add(unrealized).subtract(costs), trades);
    }

    /** The live strategy's sizing rules, mirrored (target, caps, long-only clamp). */
    private static BigDecimal quantity(Config cfg, TradeSignal signal, BigDecimal mult, Position held) {
        BigDecimal notionalPerUnit = signal.price().multiply(mult);
        BigDecimal qty = cfg.targetNotional().divide(notionalPerUnit, 0, RoundingMode.DOWN);
        if (qty.signum() <= 0) {
            if (notionalPerUnit.compareTo(cfg.maxOrderNotional()) > 0) {
                return null;
            }
            qty = BigDecimal.ONE;
        }
        BigDecimal heldQty = held.quantity();
        if (!cfg.allowShort() && signal.side() == Side.SELL) {
            if (heldQty.signum() <= 0) {
                return null;
            }
            qty = qty.min(heldQty);
        }
        boolean sameDirection = (heldQty.signum() > 0) == (signal.side() == Side.BUY);
        if (heldQty.signum() != 0 && sameDirection
                && heldQty.abs().multiply(notionalPerUnit).compareTo(cfg.maxPositionNotional()) >= 0) {
            return null;
        }
        return qty;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(io.jethro.domain.Decimals.PNL_SCALE, RoundingMode.HALF_EVEN);
    }
}
