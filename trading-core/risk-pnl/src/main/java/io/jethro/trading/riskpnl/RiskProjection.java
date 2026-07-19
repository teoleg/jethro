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
 * <p>Currency: position rows stay in each instrument's currency; rollups convert to USD
 * at the live FX mark ({@link FxConversion}). A rollup containing a currency that cannot
 * be converted (no spot-vs-USD mark yet) is reported as {@code MIXED} rather than
 * silently summed as one number. A configurable per-book base currency (non-USD
 * reporting) is deferred — see docs/deferred-register.md.
 *
 * <p>Thread-safety: all mutation and reads are synchronized. Volume is low (fills are
 * rare, marks and snapshots ~1Hz), so a single monitor is simpler than finer locking and
 * never on the tick hot path (invariant 7).
 */
public final class RiskProjection {

    private static final int SCALE = Decimals.PNL_SCALE;
    private static final RoundingMode ROUND = RoundingMode.HALF_EVEN;

    private final InstrumentRefSource refs;
    private final SwapDv01Source swapDv01;

    private final Map<String, Position> positions = new HashMap<>();
    private final Map<String, BigDecimal> realized = new HashMap<>();
    // Realized P&L translated to USD and FROZEN at each closing fill's FX rate (ADR-0037) —
    // the clean figure that does not re-wander with spot once a book is flat.
    private final Map<String, BigDecimal> realizedBaseUsd = new HashMap<>();
    // Realized P&L booked while no *USD pair mark was available to lock it — held in the
    // instrument currency and locked lazily at the first snapshot that can convert it.
    private final Map<String, BigDecimal> realizedPendingCcy = new HashMap<>();
    private final Set<String> seenFills = new HashSet<>();
    private final Map<String, MarkPoint> marks = new HashMap<>();

    private record MarkPoint(BigDecimal price, long asOfMillis) {
    }

    public RiskProjection(InstrumentRefSource refs) {
        this(refs, SwapDv01Source.NONE);
    }

    /** With live swap pricing: SWAP positions value at the LIVE per-lot DV01 × 100 instead of
     *  the V9 inception-constant multiplier (the annuity drifts with the curve). */
    public RiskProjection(InstrumentRefSource refs, SwapDv01Source swapDv01) {
        this.refs = refs;
        this.swapDv01 = swapDv01;
    }

    /** Applies a fill to the positions projection. Idempotent on {@link Fill#fillId()}. */
    public synchronized void applyFill(Fill fill) {
        if (!seenFills.add(fill.fillId())) {
            return;
        }
        String key = key(fill.bookId().value(), fill.instrumentId().value());
        InstrumentRef ref = ref(fill.instrumentId().value());
        Position pos = positions.getOrDefault(key, Position.flat(fill.bookId(), fill.instrumentId()));
        // Realized P&L on a closing fill monetizes at TODAY's multiplier (for a swap, the live
        // annuity you'd actually monetize at) — same convention as the unrealized leg.
        Positions.FillApplication applied =
                Positions.applyFill(pos, fill, effectiveMultiplier(fill.instrumentId().value(), ref));
        positions.put(key, applied.position());
        // The fee is a SEPARATE cash cost (ADR-0025): booked against realized immediately —
        // it is money already gone, whatever the position later does.
        BigDecimal ccyDelta = applied.realizedPnl().subtract(fill.fee());
        realized.merge(key, ccyDelta, BigDecimal::add);
        // Lock the USD value NOW, at the fill's FX rate (ADR-0037 clean realized). If no *USD
        // pair mark exists yet to convert this currency, hold it pending and lock it later.
        String ccy = ref.currency();
        FxConversion fx = fxFromMarks();
        if (fx.canConvert(ccy, "USD")) {
            realizedBaseUsd.merge(key, fx.convert(ccyDelta, ccy, "USD"), BigDecimal::add);
        } else {
            realizedPendingCcy.merge(key, ccyDelta, BigDecimal::add);
        }
    }

    /** Records the latest mark for an instrument (drives unrealized PnL and exposure). */
    public synchronized void applyMark(String instrumentId, BigDecimal price, long asOfMillis) {
        marks.put(instrumentId, new MarkPoint(price, asOfMillis));
    }

    /** Builds a consolidated snapshot as of {@code nowMillis}. */
    public synchronized ConsolidatedRisk snapshot(long nowMillis) {
        // FX built once from the LIVE *USD pair marks; used for the base-currency lock below
        // AND the rollup translation, so both name the same marks.
        FxConversion fx = fxFromMarks();
        List<PositionRisk> rows = new ArrayList<>();
        for (Map.Entry<String, Position> entry : positions.entrySet()) {
            Position pos = entry.getValue();
            String posKey = entry.getKey();
            BigDecimal realizedPnl = p8(realized.getOrDefault(posKey, BigDecimal.ZERO));
            if (pos.isFlat() && realizedPnl.signum() == 0) {
                continue; // fully closed and nothing realized — no signal
            }
            InstrumentRef ref = ref(pos.instrumentId().value());
            BigDecimal realizedBase = lockedRealizedUsd(posKey, ref.currency(), fx);
            MarkPoint m = marks.get(pos.instrumentId().value());
            boolean hasMark = m != null;
            long age = hasMark ? Math.max(0, nowMillis - m.asOfMillis()) : -1;
            BigDecimal qty = pos.quantity();

            BigDecimal multiplier = effectiveMultiplier(pos.instrumentId().value(), ref);
            BigDecimal unrealized = hasMark
                    ? p8(qty.multiply(m.price().subtract(pos.avgCost())).multiply(multiplier))
                    : zero();
            // Exposure: gross NOTIONAL for notional-quoted instruments (a swap lot = $1M —
            // qty × par-rate × DV01-multiplier would understate it ~5×); price × multiplier
            // for everything else. P&L above is unaffected — only the exposure measure.
            BigDecimal net = ref.notionalPerLot() != null
                    ? p8(qty.multiply(ref.notionalPerLot()))
                    : (hasMark ? p8(qty.multiply(m.price()).multiply(multiplier)) : zero());

            rows.add(new PositionRisk(
                    pos.bookId().value(), pos.instrumentId().value(), ref.assetClass(), ref.currency(),
                    qty, p8(pos.avgCost()), hasMark ? p8(m.price()) : zero(), hasMark, age,
                    realizedPnl, p8(realizedBase), unrealized, net, net.abs()));
        }
        rows.sort((a, b) -> b.grossExposure().compareTo(a.grossExposure()));

        // Cross-currency rollups (quant-engine phase 3, ADR-0020): positions stay in their
        // instrument currency; buckets and totals convert to USD via Strata's FxMatrix
        // built from the LIVE *USD pair marks the sim/feed is already publishing — every
        // conversion names its FX mark. An unconvertible currency keeps the honest MIXED
        // marker rather than silently mis-summing (finance-math rule).
        return new ConsolidatedRisk(nowMillis, totals(rows, fx),
                rollup(rows, PositionRisk::assetClass, fx), rollup(rows, PositionRisk::bookId, fx), rows);
    }

    /**
     * The USD-locked realized P&amp;L for a position (ADR-0037): the sum already frozen at each
     * closing fill's FX rate, plus any amount that was booked with no rate available then and
     * can now be locked at the current rate (locked here and cached). If the currency still has
     * no *USD pair mark, the raw local-currency remainder is returned so the MIXED bucket sums
     * it honestly — clean and comprehensive coincide there, as they must without an FX rate.
     */
    private BigDecimal lockedRealizedUsd(String posKey, String ccy, FxConversion fx) {
        BigDecimal locked = realizedBaseUsd.getOrDefault(posKey, BigDecimal.ZERO);
        BigDecimal pending = realizedPendingCcy.getOrDefault(posKey, BigDecimal.ZERO);
        if (pending.signum() != 0 && fx.canConvert(ccy, "USD")) {
            locked = locked.add(fx.convert(pending, ccy, "USD"));
            realizedBaseUsd.put(posKey, locked);
            realizedPendingCcy.remove(posKey);
            pending = BigDecimal.ZERO;
        }
        return pending.signum() == 0 ? locked : locked.add(pending);
    }

    /** The FX converter over the current *USD pair marks — for consumers (scenario
     *  engine) that convert alongside a snapshot with the SAME marks it was built from. */
    public synchronized FxConversion fx() {
        return fxFromMarks();
    }

    /** FX converter from the current *USD pair marks (EURUSD, GBPUSD, ...). */
    private FxConversion fxFromMarks() {
        Map<String, BigDecimal> pairs = new LinkedHashMap<>();
        marks.forEach((id, m) -> {
            if (id.length() == 6 && id.endsWith("USD")) {
                pairs.put(id, m.price());
            }
        });
        return FxConversion.fromUsdPairMarks(pairs);
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

    /** The SIGNED quantity a book holds in one instrument (positive = long); zero if
     *  flat/absent. Quantity, not notional — lets the strategy clamp a reducing order so
     *  it never crosses through flat into a short (long-only guard). */
    public synchronized BigDecimal positionQuantity(String bookId, String instrumentId) {
        Position pos = positions.get(key(bookId, instrumentId));
        return pos == null ? BigDecimal.ZERO : pos.quantity();
    }

    /**
     * The SIGNED notional a book currently holds in one instrument (positive = long) —
     * lets the strategy distinguish adding to a position from reducing it. Zero without
     * a mark or position.
     */
    public synchronized BigDecimal instrumentNetExposure(String bookId, String instrumentId) {
        Position pos = positions.get(key(bookId, instrumentId));
        if (pos == null) {
            return BigDecimal.ZERO;
        }
        return p8(exposureOf(instrumentId, pos.quantity()));
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
        InstrumentRef ref = ref(instrumentId);
        if (ref.notionalPerLot() != null) {
            return qty.multiply(ref.notionalPerLot()); // notional-quoted: no mark needed
        }
        MarkPoint m = marks.get(instrumentId);
        if (m == null) {
            return BigDecimal.ZERO;
        }
        return qty.multiply(m.price()).multiply(effectiveMultiplier(instrumentId, ref));
    }

    /**
     * The money-per-point multiplier used to value one instrument. Static contract multiplier
     * for everything except SWAP, where the LIVE per-lot DV01 × 100 replaces the V9 inception
     * constant when the curve is priced — the swap annuity drifts with rates (a 2022-style
     * +300bp move shrinks a 5Y DV01 by ~10%), so a constant multiplier mis-states P&L exactly
     * when rates move most. Worked: BUY 2 USD_IRS_5Y @ 4.04, par now 4.14, live DV01 $430/bp
     * → unrealized = 2 × (4.14 − 4.04) × 43,000 = $8,600 (static 45,000 would say $9,000).
     * No live pricing → the static multiplier (the disclosed V9 approximation), never zero.
     */
    private BigDecimal effectiveMultiplier(String instrumentId, InstrumentRef ref) {
        if ("SWAP".equals(ref.assetClass())) {
            var live = swapDv01.dv01PerLot(instrumentId);
            if (live.isPresent()) {
                return live.get().movePointRight(2); // $/bp per lot → $/point (100bp) per lot
            }
        }
        return ref.multiplier();
    }

    private static ConsolidatedRisk.Totals totals(List<PositionRisk> rows, FxConversion fx) {
        Acc acc = new Acc(fx);
        for (PositionRisk r : rows) {
            acc.add(r);
        }
        return new ConsolidatedRisk.Totals(
                p8(acc.cleanRealized), p8(acc.unrealized), p8(acc.cleanTotal()),
                p8(acc.fxTranslation()), p8(acc.comprehensive()), p8(acc.gross), p8(acc.net));
    }

    private static List<ConsolidatedRisk.Group> rollup(List<PositionRisk> rows,
                                                       Function<PositionRisk, String> keyFn, FxConversion fx) {
        Map<String, Acc> grouped = new LinkedHashMap<>();
        for (PositionRisk r : rows) {
            grouped.computeIfAbsent(keyFn.apply(r), k -> new Acc(fx)).add(r);
        }
        List<ConsolidatedRisk.Group> out = new ArrayList<>();
        grouped.forEach((k, a) -> out.add(new ConsolidatedRisk.Group(
                k, a.currency(), p8(a.cleanRealized), p8(a.unrealized), p8(a.cleanTotal()),
                p8(a.fxTranslation()), p8(a.comprehensive()), p8(a.gross), p8(a.net), a.count)));
        out.sort((x, y) -> y.grossExposure().compareTo(x.grossExposure()));
        return out;
    }

    /**
     * Rollup accumulator reporting in USD (quant-engine phase 3). Realized P&amp;L is split
     * two ways (ADR-0037): {@code cleanRealized} is the sum of each row's FX rate LOCKED at
     * its closing fill ({@link PositionRisk#realizedPnlBase()}) — it does not re-wander with
     * spot; {@code liveRealized} re-translates the local realized at today's marks. Their
     * difference is FX-translation P&amp;L. Unrealized and exposures always translate live
     * (an open position genuinely revalues). A currency with no pair mark keeps the honest
     * MIXED marker — never a silent mis-sum — and there clean == comprehensive (no rate).
     */
    private static final class Acc {
        final FxConversion fx;
        BigDecimal cleanRealized = BigDecimal.ZERO, liveRealized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO, net = BigDecimal.ZERO;
        int count;
        boolean unconvertible;

        Acc(FxConversion fx) {
            this.fx = fx;
        }

        void add(PositionRisk r) {
            count++;
            cleanRealized = cleanRealized.add(r.realizedPnlBase()); // already USD-locked (or raw for MIXED)
            if (fx.canConvert(r.currency(), "USD")) {
                liveRealized = liveRealized.add(fx.convert(r.realizedPnl(), r.currency(), "USD"));
                unrealized = unrealized.add(fx.convert(r.unrealizedPnl(), r.currency(), "USD"));
                gross = gross.add(fx.convert(r.grossExposure(), r.currency(), "USD"));
                net = net.add(fx.convert(r.netExposure(), r.currency(), "USD"));
            } else {
                unconvertible = true;
                liveRealized = liveRealized.add(r.realizedPnl());
                unrealized = unrealized.add(r.unrealizedPnl());
                gross = gross.add(r.grossExposure());
                net = net.add(r.netExposure());
            }
        }

        /** Clean trading P&L: locked realized + live unrealized — flat book ⇒ static. */
        BigDecimal cleanTotal() {
            return cleanRealized.add(unrealized);
        }

        /** FX revaluation of foreign realized cash: live translation − the locked figure. */
        BigDecimal fxTranslation() {
            return liveRealized.subtract(cleanRealized);
        }

        /** Actual book-value change: clean trading P&L + FX translation. Risk controls read this. */
        BigDecimal comprehensive() {
            return cleanTotal().add(fxTranslation());
        }

        String currency() {
            return unconvertible ? "MIXED" : "USD";
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
