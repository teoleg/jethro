package io.jethro.app.hypothesis;

import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deterministic idempotency guard (ADR-0022 follow-up, ADR-0054): the same EVENT never re-fires a
 *  hypothesis however it is reworded; a new event, a new direction, or (unclassified) new news does. */
class HypothesisIdempotencyTest {

    private static final long WINDOW = 4 * 60 * 60 * 1_000L;

    private static Hypothesis h(String instrument, Side dir, String thesis, String... sources) {
        return new Hypothesis(UUID.randomUUID().toString(), instrument, dir,
                Hypothesis.Horizon.SWING, Hypothesis.Conviction.MEDIUM, thesis, List.of(sources), null);
    }

    /** A call carrying a classified event key (ADR-0054), with its own thesis and sources. */
    private static Hypothesis hEvent(String instrument, Side dir, String thesis,
                                     Hypothesis.EventKey.Catalyst catalyst, String entity, LocalDate date,
                                     String... sources) {
        return new Hypothesis(UUID.randomUUID().toString(), instrument, dir,
                Hypothesis.Horizon.SWING, Hypothesis.Conviction.MEDIUM, thesis, List.of(sources),
                new Hypothesis.EventKey(catalyst, entity, date));
    }

    @Test
    void sameNewsDoesNotReFire() {
        var guard = new HypothesisIdempotency(WINDOW);
        var first = h("AAPL", Side.BUY, "Upbeat earnings support a long.", "news-3");
        assertFalse(guard.isDuplicate(first, 0));
        guard.markFired(first, 0);

        // Same instrument + direction + same source news, reworded thesis, a cycle later.
        var repeat = h("AAPL", Side.BUY, "Strong earnings beat argues for upside.", "news-3");
        assertTrue(guard.isDuplicate(repeat, 60_000), "same news must not re-trigger");
    }

    @Test
    void newNewsFires() {
        var guard = new HypothesisIdempotency(WINDOW);
        guard.markFired(h("AAPL", Side.BUY, "Earnings beat.", "news-3"), 0);
        assertFalse(guard.isDuplicate(h("AAPL", Side.BUY, "Fresh upgrade note.", "news-9"), 60_000),
                "a genuinely new headline is a new trigger");
    }

    @Test
    void oppositeDirectionFires() {
        var guard = new HypothesisIdempotency(WINDOW);
        guard.markFired(h("AAPL", Side.BUY, "Bullish on the beat.", "news-3"), 0);
        assertFalse(guard.isDuplicate(h("AAPL", Side.SELL, "Bearish reversal risk.", "news-3"), 0),
                "the opposite call on the same news is a distinct trigger");
    }

    @Test
    void windowExpiryAllowsReTrigger() {
        var guard = new HypothesisIdempotency(WINDOW);
        guard.markFired(h("ES", Side.BUY, "Momentum.", "news-1"), 0);
        assertTrue(guard.isDuplicate(h("ES", Side.BUY, "Momentum.", "news-1"), WINDOW - 1));
        assertFalse(guard.isDuplicate(h("ES", Side.BUY, "Momentum.", "news-1"), WINDOW + 1),
                "after the window a recurring theme may re-trigger");
    }

    @Test
    void noSourcesFallsBackToNormalizedThesisText() {
        var guard = new HypothesisIdempotency(WINDOW);
        guard.markFired(h("EURUSD", Side.SELL, "Dollar strength pressures the euro."), 0);
        // Same thesis, only case/punctuation/whitespace differ → the same call.
        assertTrue(guard.isDuplicate(h("EURUSD", Side.SELL, "dollar   strength pressures the euro"), 60_000));
        // A different thesis → a distinct trigger (semantic rewordings are the model's job, not
        // the deterministic floor's — the guard keys on source-news ids when the model cites them).
        assertFalse(guard.isDuplicate(h("EURUSD", Side.SELL, "ECB dovish surprise widens the rate gap."), 60_000));
    }

    @Test
    void withinCycleDuplicatesCollapse() {
        var guard = new HypothesisIdempotency(WINDOW);
        var a = h("AAPL", Side.BUY, "Beat.", "news-3");
        assertFalse(guard.isDuplicate(a, 0));
        guard.markFired(a, 0);
        assertTrue(guard.isDuplicate(h("AAPL", Side.BUY, "Beat again.", "news-3"), 0),
                "a second identical-news call in the same cycle is caught");
    }

    @Test
    void signatureIsOrderIndependentForSources() {
        assertEquals(
                HypothesisIdempotency.key(h("AAPL", Side.BUY, "x", "news-3", "news-1")),
                HypothesisIdempotency.key(h("AAPL", Side.BUY, "x", "news-1", "news-3")));
    }

    // ---- ADR-0054: event-keyed identity ----

    @Test
    void sameEventReWordedFromDifferentArticlesDedups() {
        var guard = new HypothesisIdempotency(WINDOW);
        LocalDate day = LocalDate.of(2026, 7, 20);
        // The FOMC cut, reported by two outlets → two different articles, two different theses, but
        // ONE event. The pre-ADR-0054 guard (source ids OR thesis text) would have let the second slip.
        var reuters = hEvent("ES", Side.BUY, "Fed cut lifts risk; go long the index.",
                Hypothesis.EventKey.Catalyst.FOMC, "MARKET", day, "reuters-101");
        assertFalse(guard.isDuplicate(reuters, 0));
        guard.markFired(reuters, 0);

        var bloomberg = hEvent("ES", Side.BUY, "Dovish FOMC surprise argues for upside.",
                Hypothesis.EventKey.Catalyst.FOMC, "MARKET", day, "bloomberg-777");
        assertTrue(guard.isDuplicate(bloomberg, 60_000),
                "same event (FOMC|MARKET|day), different article and wording → still a duplicate");
    }

    @Test
    void differentEventOnSameNameFires() {
        var guard = new HypothesisIdempotency(WINDOW);
        var earnings = hEvent("AAPL", Side.BUY, "Earnings beat.",
                Hypothesis.EventKey.Catalyst.EARNINGS, "AAPL", LocalDate.of(2026, 7, 20), "n1");
        guard.markFired(earnings, 0);
        // A genuinely different catalyst the next day → a new event, fires once.
        var upgrade = hEvent("AAPL", Side.BUY, "Analyst upgrade.",
                Hypothesis.EventKey.Catalyst.RATING, "AAPL", LocalDate.of(2026, 7, 21), "n2");
        assertFalse(guard.isDuplicate(upgrade, 60_000), "a different event is a new trigger");
    }

    @Test
    void unclassifiedEventFallsBackToTheNewsGuard() {
        var guard = new HypothesisIdempotency(WINDOW);
        // OTHER = the model couldn't classify a discrete event → the guard falls back to source ids,
        // so the same news still dedups and genuinely new news still fires (the pre-ADR-0054 behavior).
        var first = hEvent("AMZN", Side.BUY, "General strength.",
                Hypothesis.EventKey.Catalyst.OTHER, "AMZN", LocalDate.of(2026, 7, 20), "news-5");
        guard.markFired(first, 0);
        var sameNews = hEvent("AMZN", Side.BUY, "Reworded strength.",
                Hypothesis.EventKey.Catalyst.OTHER, "AMZN", LocalDate.of(2026, 7, 20), "news-5");
        assertTrue(guard.isDuplicate(sameNews, 60_000), "OTHER → source-id fallback still dedups same news");
        var newNews = hEvent("AMZN", Side.BUY, "Reworded strength.",
                Hypothesis.EventKey.Catalyst.OTHER, "AMZN", LocalDate.of(2026, 7, 20), "news-9");
        assertFalse(guard.isDuplicate(newNews, 60_000), "OTHER → new source id still fires");
    }
}
