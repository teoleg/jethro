package io.jethro.trading.marketdata.sim;

/**
 * Scaled-long bid/ask synthesis around a mid for the sim feed (ADR-0025): the SAME spread
 * numbers the execution cost model uses, so the quoted touch and the synthetic touch agree
 * by construction. Spread is carried in <b>centi-bps</b> (bps × 100, so SWAP's 0.4bp is the
 * exact integer 40 — no floats on the hot path).
 *
 * <pre>
 *   price-quoted:  half = mid × s / (2 × 10⁶)          (s centi-bps → fraction = s/10⁶)
 *   rate-quoted:   half = s × 50                       (1bp of rate = 0.01 quote units
 *                                                       = 10,000 scaled; s/100 bp × 10⁴ / 2)
 * </pre>
 *
 * Worked: mid 190.000000 (190,000,000 scaled), EQUITY 5bp → s=500: half = 190e6×500/2e6 =
 * 47,500 → bid 189,952,500 / ask 190,047,500 = 190.0475 — exactly the executor's synthetic
 * touch of 190 × 1.00025.
 */
public final class Quotes {

    /** Per-instrument quote synthesis spec; spread in centi-bps (bps × 100). */
    public record QuoteSpec(int spreadCentiBps, boolean rateQuoted) {
    }

    /** null → no quotes for this instrument (e.g. curve pseudo-quotes). */
    public interface QuoteSpecSource {
        QuoteSpec specFor(String instrumentId);
    }

    private Quotes() {
    }

    public static long bidScaled(long midScaled, QuoteSpec spec) {
        return midScaled - half(midScaled, spec);
    }

    public static long askScaled(long midScaled, QuoteSpec spec) {
        return midScaled + half(midScaled, spec);
    }

    private static long half(long midScaled, QuoteSpec spec) {
        if (spec.rateQuoted()) {
            return spec.spreadCentiBps() * 50L;
        }
        return midScaled * spec.spreadCentiBps() / 2_000_000L;
    }
}
