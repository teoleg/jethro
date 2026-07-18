package io.jethro.app.hypothesis;

/**
 * Pure directional-balance logic for the hypothesis layer (ADR-0036): label the recent long/short
 * skew and format the advisory line fed back to the model. Descriptive only — never a risk limit
 * (the net-exposure guardrail is the limit) and never a quota (a call without a reason is worse
 * than a skew).
 */
final class HypothesisBalance {

    private static final int MIN_CALLS = 3;      // too few to call a skew
    private static final double SKEW_FRACTION = 0.75;

    private HypothesisBalance() {
    }

    /** "skewing long" / "skewing short" / "balanced" from the live long/short counts. */
    static String skew(int longs, int shorts) {
        int total = longs + shorts;
        if (total < MIN_CALLS) {
            return "balanced";
        }
        double longFraction = (double) longs / total;
        if (longFraction >= SKEW_FRACTION) {
            return "skewing long";
        }
        if (longFraction <= 1.0 - SKEW_FRACTION) {
            return "skewing short";
        }
        return "balanced";
    }

    /** The balance line for the prompt, or "" when there are too few calls to matter. */
    static String promptLine(int longs, int shorts) {
        if (longs + shorts < MIN_CALLS) {
            return "";
        }
        return "recent calls: " + longs + " long, " + shorts + " short (" + skew(longs, shorts) + ")";
    }
}
