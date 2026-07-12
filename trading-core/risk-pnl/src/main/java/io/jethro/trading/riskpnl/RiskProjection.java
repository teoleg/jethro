package io.jethro.trading.riskpnl;

import io.jethro.domain.Decimals;
import io.jethro.domain.Fill;
import io.jethro.domain.Position;
import io.jethro.domain.Positions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The positions/PnL projection (invariant 3: only this owns the positions projection).
 * Consumes fills and marks and produces {@link ConsolidatedRisk} snapshots. Average-cost
 * accounting is delegated to {@link Positions}; this class adds mark-to-market, exposure,
 * and the per-asset-class / per-book rollups.
 *
 * <p>Idempotent under at-least-once redelivery (invariant 6): a fill whose {@code fillId}
 * has been seen is ignored, so applying the same fill twice leaves state unchanged.
 *
 * <p>Currency: figures are in each instrument's currency; cross-currency conversion to a
 * book base currency is deferred (all dev instruments are USD) — a rollup spanning
 * currencies is reported as {@code MIXED} rather than silently summed as one number.
 *
 * <p>Thread-safety: all mutation and reads are synchronized. Volume is low (fills are
 * rare, marks and snapshots ~1Hz), so a single monitor is simpler than finer locking and
 * never on the tick hot path (invariant 7).
 */
public final class RiskProjection {

    private static final int SCALE = Decimals.PNL_SCALE;
    private static final RoundingMode ROUND = RoundingMode.HALF_EVEN;

    private final InstrumentRefSource refs;

    private final Map<String, Position> positions = new HashMap<>();
    private final Map<String, BigDecimal> realized = new HashMap<>();
    private final Set<String> seenFills = new HashSet<>();
    private final Map<String, MarkPoint> marks = new HashMap<>();

    private record MarkPoint(BigDecimal price, long asOfMillis) {
    }

    public RiskProjection(InstrumentRefSource refs) {
        this.refs = refs;
    }

    /** Applies a fill to the positions projection. Idempotent on {@link Fill#fillId()}. */
    public synchronized void applyFill(Fill fill) {
        if (!seenFills.add(fill.fillId())) {
            return;
        }
        String key = key(fill.bookId().value(), fill.instrumentId().value());
        InstrumentRef ref = ref(fill.instrumentId().value());
        Position pos = positions.getOrDefault(key, Position.flat(fill.bookId(), fill.instrumentId()));
        Positions.FillApplication applied = Positions.applyFill(pos, fill, ref.multiplier());
        positions.put(key, applied.position());
        realized.merge(key, applied.realizedPnl(), BigDecimal::add);
    }

    /** Records the latest mark for an instrument (drives unrealized PnL and exposure). */
    public synchronized void applyMark(String instrumentId, BigDecimal price, long asOfMillis) {
        marks.put(instrumentId, new MarkPoint(price, asOfMillis));
    }

    /** Builds a consolidated snapshot as of {@code nowMillis}. */
    public synchronized ConsolidatedRisk snapshot(long nowMillis) {
        List<PositionRisk> rows = new ArrayList<>();
        for (Map.Entry<String, Position> entry : positions.entrySet()) {
            Position pos = entry.getValue();
            BigDecimal realizedPnl = p8(realized.getOrDefault(entry.getKey(), BigDecimal.ZERO));
            if (pos.isFlat() && realizedPnl.signum() == 0) {
                continue; // fully closed and nothing realized — no signal
            }
            InstrumentRef ref = ref(pos.instrumentId().value());
            MarkPoint m = marks.get(pos.instrumentId().value());
            boolean hasMark = m != null;
            long age = hasMark ? Math.max(0, nowMillis - m.asOfMillis()) : -1;
            BigDecimal qty = pos.quantity();

            BigDecimal unrealized = hasMark
                    ? p8(qty.multiply(m.price().subtract(pos.avgCost())).multiply(ref.multiplier()))
                    : zero();
            BigDecimal net = hasMark ? p8(qty.multiply(m.price()).multiply(ref.multiplier())) : zero();

            rows.add(new PositionRisk(
                    pos.bookId().value(), pos.instrumentId().value(), ref.assetClass(), ref.currency(),
                    qty, p8(pos.avgCost()), hasMark ? p8(m.price()) : zero(), hasMark, age,
                    realizedPnl, unrealized, net, net.abs()));
        }
        rows.sort((a, b) -> b.grossExposure().compareTo(a.grossExposure()));

        return new ConsolidatedRisk(nowMillis, totals(rows),
                rollup(rows, PositionRisk::assetClass), rollup(rows, PositionRisk::bookId), rows);
    }

    /** A book's gross/net exposure — for pre-trade limit checks. */
    public record Exposure(BigDecimal gross, BigDecimal net) {
    }

    /**
     * Projects a book's gross/net exposure if {@code signedQtyDelta} were applied to one
     * instrument — the basis of the pre-trade guardrail (ADR-0018). Exposure only needs the
     * resulting quantity valued at its mark, so no avgCost/fill is simulated. An instrument
     * with no mark contributes zero (can't value it).
     */
    public synchronized Exposure projectedExposure(String bookId, String instrumentId, BigDecimal signedQtyDelta) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        boolean targetSeen = false;
        for (Map.Entry<String, Position> entry : positions.entrySet()) {
            Position pos = entry.getValue();
            if (!pos.bookId().value().equals(bookId)) {
                continue;
            }
            String iid = pos.instrumentId().value();
            BigDecimal qty = pos.quantity();
            if (iid.equals(instrumentId)) {
                qty = qty.add(signedQtyDelta);
                targetSeen = true;
            }
            BigDecimal contrib = exposureOf(iid, qty);
            net = net.add(contrib);
            gross = gross.add(contrib.abs());
        }
        if (!targetSeen) {
            BigDecimal contrib = exposureOf(instrumentId, signedQtyDelta);
            net = net.add(contrib);
            gross = gross.add(contrib.abs());
        }
        return new Exposure(p8(gross), p8(net));
    }

    /**
     * Projects the |notional| the book would hold in one instrument if {@code signedQtyDelta}
     * were applied — the concentration input of the pre-trade guardrail. Zero without a mark.
     */
    public synchronized BigDecimal projectedInstrumentExposure(String bookId, String instrumentId,
                                                               BigDecimal signedQtyDelta) {
        Position pos = positions.get(key(bookId, instrumentId));
        BigDecimal qty = (pos == null ? BigDecimal.ZERO : pos.quantity()).add(signedQtyDelta);
        return p8(exposureOf(instrumentId, qty).abs());
    }

    /**
     * Projects the firm-wide gross/net exposure (across every book) if {@code signedQtyDelta}
     * were applied to one (book, instrument) — the firm-cap input of the guardrail.
     */
    public synchronized Exposure projectedFirmExposure(String bookId, String instrumentId,
                                                       BigDecimal signedQtyDelta) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        String targetKey = key(bookId, instrumentId);
        boolean targetSeen = false;
        for (Map.Entry<String, Position> entry : positions.entrySet()) {
            Position pos = entry.getValue();
            BigDecimal qty = pos.quantity();
            if (entry.getKey().equals(targetKey)) {
                qty = qty.add(signedQtyDelta);
                targetSeen = true;
            }
            BigDecimal contrib = exposureOf(pos.instrumentId().value(), qty);
            net = net.add(contrib);
            gross = gross.add(contrib.abs());
        }
        if (!targetSeen) {
            BigDecimal contrib = exposureOf(instrumentId, signedQtyDelta);
            net = net.add(contrib);
            gross = gross.add(contrib.abs());
        }
        return new Exposure(p8(gross), p8(net));
    }

    private BigDecimal exposureOf(String instrumentId, BigDecimal qty) {
        MarkPoint m = marks.get(instrumentId);
        if (m == null) {
            return BigDecimal.ZERO;
        }
        return qty.multiply(m.price()).multiply(ref(instrumentId).multiplier());
    }

    private static ConsolidatedRisk.Totals totals(List<PositionRisk> rows) {
        BigDecimal realized = BigDecimal.ZERO, unrealized = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO, net = BigDecimal.ZERO;
        for (PositionRisk r : rows) {
            realized = realized.add(r.realizedPnl());
            unrealized = unrealized.add(r.unrealizedPnl());
            gross = gross.add(r.grossExposure());
            net = net.add(r.netExposure());
        }
        return new ConsolidatedRisk.Totals(
                p8(realized), p8(unrealized), p8(realized.add(unrealized)), p8(gross), p8(net));
    }

    private static List<ConsolidatedRisk.Group> rollup(List<PositionRisk> rows, Function<PositionRisk, String> keyFn) {
        Map<String, Acc> grouped = new LinkedHashMap<>();
        for (PositionRisk r : rows) {
            grouped.computeIfAbsent(keyFn.apply(r), k -> new Acc()).add(r);
        }
        List<ConsolidatedRisk.Group> out = new ArrayList<>();
        grouped.forEach((k, a) -> out.add(new ConsolidatedRisk.Group(
                k, a.currency(), p8(a.realized), p8(a.unrealized),
                p8(a.realized.add(a.unrealized)), p8(a.gross), p8(a.net), a.count)));
        out.sort((x, y) -> y.grossExposure().compareTo(x.grossExposure()));
        return out;
    }

    /** Mutable rollup accumulator; collapses currency to MIXED when a bucket spans currencies. */
    private static final class Acc {
        BigDecimal realized = BigDecimal.ZERO, unrealized = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO, net = BigDecimal.ZERO;
        int count;
        String currency;
        boolean mixed;

        void add(PositionRisk r) {
            realized = realized.add(r.realizedPnl());
            unrealized = unrealized.add(r.unrealizedPnl());
            gross = gross.add(r.grossExposure());
            net = net.add(r.netExposure());
            count++;
            if (currency == null) {
                currency = r.currency();
            } else if (!currency.equals(r.currency())) {
                mixed = true;
            }
        }

        String currency() {
            return mixed ? "MIXED" : (currency == null ? "USD" : currency);
        }
    }

    private InstrumentRef ref(String instrumentId) {
        return refs.find(instrumentId).orElseGet(() -> InstrumentRef.unknown(instrumentId));
    }

    private static String key(String bookId, String instrumentId) {
        return bookId + "|" + instrumentId;
    }

    private static BigDecimal p8(BigDecimal value) {
        return value.setScale(SCALE, ROUND);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, ROUND);
    }
}
