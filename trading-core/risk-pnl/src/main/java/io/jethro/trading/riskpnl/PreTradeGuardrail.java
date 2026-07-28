package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;

/**
 * Deterministic pre-trade limit check (ADR-0018): would this order breach the book's
 * gross/net caps, its per-instrument concentration cap, the firm-wide caps — or add risk
 * to a book already past its max-loss? Guardrails are code, never a model (invariant 7);
 * exact BigDecimal comparisons (invariant 1). Returns a rejection reason, or empty.
 *
 * <p>Exposure exactly at a cap is allowed; only strictly exceeding rejects. A loss-breached
 * book may still <em>reduce</em> risk (projected gross below current) so it can flatten.
 *
 * <p><b>In-flight reservations:</b> the projection only reflects an order once its fill
 * event round-trips the broker, so several orders in one burst would each be checked
 * against the same stale exposure. When {@code reserve} is set (the order path), an
 * approved order's exposure delta is reserved for {@value #RESERVATION_MILLIS}ms and
 * counted by subsequent checks. Suggestion-only checks pass {@code reserve=false} and
 * never create phantom reservations. Reservations may briefly double-count with the
 * applied fill — conservative over-blocking, never under-blocking.
 */
public final class PreTradeGuardrail {

    private static final long RESERVATION_MILLIS = 10_000;

    /**
     * Whether the market session currently permits OPENING (risk-adding) trades (ADR-0115). Injected
     * from above (the app wires it to the wall-clock calendar); a continuous sim/replay tape passes
     * {@code () -> true}. Deterministic — no model (invariant 7).
     */
    @FunctionalInterface
    public interface SessionGate {
        boolean riskAddingAllowed();
    }

    private final RiskProjection projection;
    private final RiskLimitSource limits;
    private final SessionGate sessionGate;

    private record Reservation(long expiresAt, String bookId, BigDecimal grossDelta, BigDecimal netDelta) {
    }

    private final Deque<Reservation> reservations = new ArrayDeque<>();

    /** No session gate — risk-adding always permitted (sim/replay and tests). */
    public PreTradeGuardrail(RiskProjection projection, RiskLimitSource limits) {
        this(projection, limits, () -> true);
    }

    public PreTradeGuardrail(RiskProjection projection, RiskLimitSource limits, SessionGate sessionGate) {
        this.projection = projection;
        this.limits = limits;
        this.sessionGate = sessionGate;
    }

    /** Read-only check (no reservation) — used for suggestions. */
    public Optional<String> rejectionReason(String bookId, String instrumentId, BigDecimal signedQuantity) {
        return rejectionReason(bookId, instrumentId, signedQuantity, false);
    }

    /**
     * @param signedQuantity order quantity signed by side (BUY positive, SELL negative).
     * @param reserve        true on the order path: an approval reserves the exposure delta
     *                       so in-flight orders count against subsequent checks.
     * @return a rejection reason if the order would breach a limit, else empty (approved).
     */
    public synchronized Optional<String> rejectionReason(String bookId, String instrumentId,
                                                         BigDecimal signedQuantity, boolean reserve) {
        long now = System.currentTimeMillis();
        expireReservations(now);

        RiskLimits book = limits.limitsFor(bookId);
        RiskProjection.Exposure current = projection.projectedExposure(bookId, instrumentId, BigDecimal.ZERO);
        RiskProjection.Exposure projected = projection.projectedExposure(bookId, instrumentId, signedQuantity);
        BigDecimal projGross = projected.gross().add(reservedGross(bookId));
        BigDecimal projNet = projected.net().add(reservedNet(bookId));
        boolean addsRisk = projected.gross().compareTo(current.gross()) > 0;

        // Session gate (ADR-0115): outside the market session only risk-reducing (flattening) orders
        // are allowed. A continuous tape (sim/replay) is always "open". This stops new exposure being
        // opened after-hours/overnight/weekends on a thin or stale tape that then cannot be managed
        // until the reopen — while a position can always be flattened.
        if (addsRisk && !sessionGate.riskAddingAllowed()) {
            return Optional.of("market session is closed — only risk-reducing orders allowed (ADR-0115)");
        }

        // Loss gate: a book past its max loss may only reduce risk.
        if (RiskLimits.isSet(book.maxLossPnl()) && addsRisk) {
            BigDecimal loss = bookLoss(bookId, now);
            if (loss.compareTo(book.maxLossPnl()) >= 0) {
                return Optional.of(bookId + " is over its max loss (" + plain(loss) + " vs "
                        + plain(book.maxLossPnl()) + ") — only risk-reducing orders allowed");
            }
        }
        if (RiskLimits.isSet(book.maxGrossExposure()) && projGross.compareTo(book.maxGrossExposure()) > 0) {
            return Optional.of("gross exposure " + plain(projGross) + " would exceed "
                    + bookId + " limit " + plain(book.maxGrossExposure()));
        }
        if (RiskLimits.isSet(book.maxNetExposure()) && projNet.abs().compareTo(book.maxNetExposure()) > 0) {
            return Optional.of("net exposure " + plain(projNet.abs()) + " would exceed "
                    + bookId + " limit " + plain(book.maxNetExposure()));
        }
        if (RiskLimits.isSet(book.maxInstrumentExposure())) {
            BigDecimal instrExposure =
                    projection.projectedInstrumentExposure(bookId, instrumentId, signedQuantity);
            if (instrExposure.compareTo(book.maxInstrumentExposure()) > 0) {
                return Optional.of(instrumentId + " exposure " + plain(instrExposure)
                        + " would exceed the " + bookId + " per-instrument limit "
                        + plain(book.maxInstrumentExposure()));
            }
        }

        // Firm-wide caps across every book (reservations from all books count).
        RiskLimits firm = limits.firmLimits();
        if (RiskLimits.isSet(firm.maxGrossExposure()) || RiskLimits.isSet(firm.maxNetExposure())) {
            RiskProjection.Exposure firmProjected =
                    projection.projectedFirmExposure(bookId, instrumentId, signedQuantity);
            BigDecimal firmGross = firmProjected.gross().add(reservedGross(null));
            BigDecimal firmNet = firmProjected.net().add(reservedNet(null));
            if (RiskLimits.isSet(firm.maxGrossExposure()) && firmGross.compareTo(firm.maxGrossExposure()) > 0) {
                return Optional.of("firm gross exposure " + plain(firmGross)
                        + " would exceed the firm limit " + plain(firm.maxGrossExposure()));
            }
            if (RiskLimits.isSet(firm.maxNetExposure()) && firmNet.abs().compareTo(firm.maxNetExposure()) > 0) {
                return Optional.of("firm net exposure " + plain(firmNet.abs())
                        + " would exceed the firm limit " + plain(firm.maxNetExposure()));
            }
        }

        if (reserve) {
            reservations.addLast(new Reservation(now + RESERVATION_MILLIS, bookId,
                    projected.gross().subtract(current.gross()),
                    projected.net().subtract(current.net())));
        }
        return Optional.empty();
    }

    /**
     * Whether an order reduces the book's exposure to the instrument — a risk-reducing exit.
     * Structural (invariant 3 owns positions): projected book gross strictly below current
     * means the trade shrinks |position|. Such orders bypass the ADV participation cap
     * downstream — you must always be able to get out of a position. A zero/absent delta, or
     * a trade that leaves gross unchanged or higher, is not risk-reducing.
     */
    public synchronized boolean reducesRisk(String bookId, String instrumentId, BigDecimal signedQuantity) {
        if (signedQuantity == null || signedQuantity.signum() == 0) {
            return false;
        }
        RiskProjection.Exposure current = projection.projectedExposure(bookId, instrumentId, BigDecimal.ZERO);
        RiskProjection.Exposure projected = projection.projectedExposure(bookId, instrumentId, signedQuantity);
        return projected.gross().compareTo(current.gross()) < 0;
    }

    /** Current loss (positive number) of a book, zero if profitable or unknown. */
    private BigDecimal bookLoss(String bookId, long now) {
        for (ConsolidatedRisk.Group g : projection.snapshot(now).byBook()) {
            if (g.key().equals(bookId)) {
                // COMPREHENSIVE (actual loss incl. FX translation), not the clean figure (ADR-0037).
                BigDecimal pnl = g.comprehensivePnl();
                return pnl.signum() < 0 ? pnl.negate() : BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private void expireReservations(long now) {
        Iterator<Reservation> it = reservations.iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt() <= now) {
                it.remove();
            }
        }
    }

    /** Sum of unexpired reserved gross deltas for a book, or firm-wide when bookId is null. */
    private BigDecimal reservedGross(String bookId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Reservation r : reservations) {
            if (bookId == null || r.bookId().equals(bookId)) {
                if (r.grossDelta().signum() > 0) {
                    sum = sum.add(r.grossDelta());
                }
            }
        }
        return sum;
    }

    private BigDecimal reservedNet(String bookId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Reservation r : reservations) {
            if (bookId == null || r.bookId().equals(bookId)) {
                sum = sum.add(r.netDelta());
            }
        }
        return sum;
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
