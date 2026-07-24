package io.jethro.app.training;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The ADR-0053 validation gate: purged, embargoed WALK-FORWARD cross-validation with COST-AWARE
 * scoring — the protocol where a learned signal's edge is actually won or lost. It exists to make the
 * three quiet killers hard: look-ahead leakage (train strictly in the past, and PURGE training rows
 * whose next-day label would peek into the test window, plus an embargo gap), overfitting (out-of-sample
 * folds only), and self-deception (every decision scored net of transaction cost, and the signal must
 * beat BOTH a coin-flip after costs AND our existing momentum/mean-reversion, or it does not ship —
 * ADR-0053, ADR-0049).
 *
 * <p>Pure and static so it is exactly testable. The score is a decision's realised edge, not a fitted
 * price — no model number ever leaves here for positions/PnL/risk (ADR-0016 / invariant 7). Real
 * history only; a run over sim bars would grade the ADR-0026 generator, never markets.
 */
public final class WalkForwardBacktest {

    private WalkForwardBacktest() {
    }

    /** One strategy's out-of-sample tally over the pooled test rows. netReturnBps is per-opportunity. */
    public record Score(long trades, long wins, double netReturnBps, double hitRate, double coverage) {
    }

    public record Result(boolean sufficient, int folds, int trainableFolds, long testRows, double costBps,
                         Score learned, Score coinFlip, Score momentum, Score meanReversion,
                         boolean ships, String verdict) {

        public static Result insufficient(String why) {
            Score z = new Score(0, 0, 0, 0, 0);
            return new Result(false, 0, 0, 0, 0, z, z, z, z, false, why);
        }
    }

    public record Config(int folds, int embargoDays, double costBps, int minTrainRows,
                         int iterations, double learningRate, double l2) {
    }

    /** Accumulates net-of-cost outcomes for one strategy across every fold's test rows. */
    private static final class Tally {
        long trades, wins;
        double sumNet;

        void record(int direction, double forwardReturn, double cost) {
            if (direction == 0) {
                return; // abstained — a real, costless choice; counts against coverage, not P&L
            }
            double net = direction * forwardReturn - cost;
            sumNet += net;
            trades++;
            if (net > 0) {
                wins++;
            }
        }

        Score score(long testRows) {
            double netBps = testRows == 0 ? 0 : 1e4 * sumNet / testRows; // realised edge per opportunity
            double hit = trades == 0 ? 0 : (double) wins / trades;
            double cov = testRows == 0 ? 0 : (double) trades / testRows;
            return new Score(trades, wins, netBps, hit, cov);
        }
    }

    /**
     * Runs the purged walk-forward CV over one pooled, time-ordered feature set (all instruments).
     * @param rows every labelled feature row; will be sorted by (day, instrument) internally.
     */
    public static Result run(List<FeatureBuilder.FeatureRow> rows, Config cfg) {
        if (rows == null || rows.size() < Math.max(2 * cfg.minTrainRows(), 50)) {
            return Result.insufficient("not enough labelled history yet — the learned signal needs more bars");
        }
        List<FeatureBuilder.FeatureRow> all = new ArrayList<>(rows);
        all.sort(Comparator.comparingLong(FeatureBuilder.FeatureRow::epochDay)
                .thenComparing(FeatureBuilder.FeatureRow::instrument));

        long[] days = all.stream().mapToLong(FeatureBuilder.FeatureRow::epochDay).distinct().sorted().toArray();
        if (days.length < cfg.folds() + 2) {
            return Result.insufficient("too few distinct trading days for " + cfg.folds() + " walk-forward folds");
        }
        double cost = Math.abs(cfg.costBps()) / 1e4; // round-trip cost as a return fraction
        // Reserve the first half for the initial training window; split the rest into K test blocks.
        int firstTest = days.length / 2;
        int testDays = days.length - firstTest;
        int blocks = cfg.folds();

        Tally learned = new Tally(), coin = new Tally(), mom = new Tally(), rev = new Tally();
        long testRowCount = 0;
        int trainableFolds = 0;

        for (int f = 0; f < blocks; f++) {
            int lo = firstTest + (int) ((long) f * testDays / blocks);
            int hi = firstTest + (int) ((long) (f + 1) * testDays / blocks); // exclusive
            if (lo >= hi) {
                continue;
            }
            long testStartDay = days[lo];
            long testEndDay = days[hi - 1];
            // PURGE + EMBARGO: a train row at day t carries a t+1 label; drop it if t+1 could observe the
            // test window. Keep only t ≤ testStart − 2 − embargo (−1 for the label horizon, −1 exclusivity).
            long trainCutoff = testStartDay - 2 - cfg.embargoDays();

            List<FeatureBuilder.FeatureRow> train = new ArrayList<>();
            List<FeatureBuilder.FeatureRow> test = new ArrayList<>();
            for (FeatureBuilder.FeatureRow r : all) {
                if (r.epochDay() <= trainCutoff) {
                    train.add(r);
                } else if (r.epochDay() >= testStartDay && r.epochDay() <= testEndDay) {
                    test.add(r);
                }
            }
            if (train.size() < cfg.minTrainRows() || test.isEmpty()) {
                continue; // not enough purged history for this fold yet
            }
            trainableFolds++;
            testRowCount += test.size();

            LogisticSignalModel model = new LogisticSignalModel(cfg.iterations(), cfg.learningRate(), cfg.l2());
            model.fit(features(train), labels(train));

            for (FeatureBuilder.FeatureRow r : test) {
                double fwd = r.forwardReturn();
                learned.record(directionFromClass(model.classify(featureVector(r))), fwd, cost);
                coin.record(coinFlip(r), fwd, cost);
                mom.record(sign(r.ret5()), fwd, cost);        // momentum: ride the 5-day drift
                rev.record(-sign(r.ret5()), fwd, cost);       // mean-reversion: fade it
            }
        }

        if (trainableFolds == 0 || testRowCount == 0) {
            return Result.insufficient("no fold had enough purged training history — need a longer bar history");
        }
        Score ls = learned.score(testRowCount);
        Score cs = coin.score(testRowCount);
        Score ms = mom.score(testRowCount);
        Score rs = rev.score(testRowCount);
        double bestBaseline = Math.max(cs.netReturnBps(), Math.max(ms.netReturnBps(), rs.netReturnBps()));
        boolean ships = ls.netReturnBps() > 0 && ls.netReturnBps() > cs.netReturnBps() && ls.netReturnBps() > Math.max(ms.netReturnBps(), rs.netReturnBps());
        String verdict = ships
                ? String.format("SHIPS — %.2f bps/opportunity net of %.0f bps cost, ahead of every baseline (coin %.2f, mom %.2f, mean-rev %.2f)",
                        ls.netReturnBps(), cfg.costBps(), cs.netReturnBps(), ms.netReturnBps(), rs.netReturnBps())
                : String.format("VETOED — %.2f bps/opportunity net does not clear zero-and-baselines (best baseline %.2f bps); no durable edge, no advisory status",
                        ls.netReturnBps(), bestBaseline);
        return new Result(true, blocks, trainableFolds, testRowCount, cfg.costBps(), ls, cs, ms, rs, ships, verdict);
    }

    private static int directionFromClass(int cls) {
        return switch (cls) {
            case LogisticSignalModel.UP -> 1;
            case LogisticSignalModel.DOWN -> -1;
            default -> 0; // FLAT → abstain
        };
    }

    /** Deterministic pseudo-random long/short per row: "trade on noise, pay the spread". */
    private static int coinFlip(FeatureBuilder.FeatureRow r) {
        long h = r.epochDay() * 1099511628211L ^ r.instrument().hashCode();
        h ^= (h >>> 31);
        return (h & 1L) == 0 ? 1 : -1;
    }

    private static int sign(double v) {
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }

    private static double[] featureVector(FeatureBuilder.FeatureRow r) {
        return new double[]{r.ret1(), r.ret5(), r.ret20(), r.vol20(), r.volRatio()};
    }

    private static double[][] features(List<FeatureBuilder.FeatureRow> rows) {
        double[][] x = new double[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            x[i] = featureVector(rows.get(i));
        }
        return x;
    }

    private static int[] labels(List<FeatureBuilder.FeatureRow> rows) {
        int[] y = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            y[i] = LogisticSignalModel.labelIndex(rows.get(i).label());
        }
        return y;
    }
}
