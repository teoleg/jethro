package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.NarrativeItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Real news for the hypothesis layer (ADR-0024): Finnhub general-market and per-company
 * headlines, replacing the sim fixtures when a Finnhub key is configured. Finnhub symbols
 * are used only to <b>query</b> — every {@link NarrativeItem} keys on the internal
 * {@code instrumentId} (invariant 2, symbology never leaks). Headlines are read by the LLM
 * as text; the sentiment tag is a coarse keyword heuristic, never a number into sizing/risk
 * (invariant 1/7). Refreshes are throttled ({@code minRefreshMillis}) so the cadence of the
 * hypothesis loop doesn't hammer the free-tier API; a fetch error just yields no new items
 * (the model reasons on whatever's cached — the market path never depends on this).
 */
public final class FinnhubNarrativeFeed implements NarrativeFeed {

    /** The Finnhub news endpoints this feed reads; {@link FinnhubNewsClient} is the live impl,
     *  fakeable in tests. Implementations must never throw — return empty on any error. */
    public interface NewsSource {
        List<Article> companyNews(String finnhubSymbol);

        List<Article> generalNews();

        /** One raw article: Finnhub's numeric id, epoch-seconds timestamp, headline, summary. */
        record Article(long id, long datetimeEpochSec, String headline, String summary) {
        }
    }

    private static final int MAX_RECENT = 12;
    private static final int MAX_NAMES_PER_REFRESH = 6; // bound free-tier calls per cycle
    private static final int SEEN_CAP = 5_000;

    // Coarse keyword sentiment — clearly heuristic, only a hint for the LLM (which reads the
    // headline itself). Conservative default NEUTRAL when nothing matches (finance-math rule).
    private static final String[] BULLISH_WORDS = {
            "beat", "beats", "surge", "surges", "jump", "jumps", "soar", "rally", "rallies",
            "upgrade", "upgraded", "record", "tops", "raises guidance", "raised guidance", "profit rises"};
    private static final String[] BEARISH_WORDS = {
            "miss", "misses", "plunge", "plunges", "slump", "slumps", "downgrade", "downgraded",
            "cuts", "cut guidance", "falls", "drops", "warns", "warning", "probe", "lawsuit",
            "recall", "slashes", "layoff", "layoffs"};
    private static final String[] EARNINGS_WORDS = {
            "earnings", "eps", "guidance", "revenue", "results", "quarter", "profit", "outlook", "forecast"};

    private final NewsSource news;
    private final Map<String, String> instrumentToSymbol; // internal id → Finnhub symbol (query only)
    private final long minRefreshMillis;

    private final Deque<NarrativeItem> recent = new ArrayDeque<>();
    private final Set<Long> seen = new HashSet<>();
    private long lastRefreshMillis;
    private boolean refreshedOnce;

    public FinnhubNarrativeFeed(NewsSource news, Map<String, String> instrumentToSymbol, long minRefreshMillis) {
        this.news = news;
        this.instrumentToSymbol = new LinkedHashMap<>(instrumentToSymbol);
        this.minRefreshMillis = minRefreshMillis;
    }

    @Override
    public synchronized List<NarrativeItem> poll(String regime, List<String> singleNameInstruments, long nowMillis) {
        if (!refreshedOnce || nowMillis - lastRefreshMillis >= minRefreshMillis) {
            refresh(singleNameInstruments, nowMillis);
            lastRefreshMillis = nowMillis;
            refreshedOnce = true;
        }
        // Newest last, matching the sim feed's contract.
        List<NarrativeItem> out = new ArrayList<>(recent);
        out.sort((a, b) -> Long.compare(a.timestampMillis(), b.timestampMillis()));
        return out;
    }

    private void refresh(List<String> singleNames, long nowMillis) {
        for (NewsSource.Article a : news.generalNews()) {
            add(null, a, NarrativeItem.Category.MACRO);
        }
        int names = 0;
        for (String instrumentId : singleNames) {
            String symbol = instrumentToSymbol.get(instrumentId);
            if (symbol == null) {
                continue; // not a Finnhub-covered name — no company news to pull
            }
            for (NewsSource.Article a : news.companyNews(symbol)) {
                add(instrumentId, a, categoryFor(a));
            }
            if (++names >= MAX_NAMES_PER_REFRESH) {
                break;
            }
        }
    }

    private void add(String instrumentId, NewsSource.Article a, NarrativeItem.Category category) {
        if (a.headline() == null || a.headline().isBlank()) {
            return;
        }
        long key = a.id() != 0 ? a.id() : stableHash(a.headline());
        if (!seen.add(key)) {
            return; // already surfaced this article
        }
        if (seen.size() > SEEN_CAP) {
            seen.clear();
            seen.add(key);
        }
        long ts = a.datetimeEpochSec() > 0 ? a.datetimeEpochSec() * 1_000 : System.currentTimeMillis();
        NarrativeItem item = new NarrativeItem("finnhub-" + key, ts, category, instrumentId,
                sentiment(a.headline(), a.summary()), a.headline());
        if (recent.size() == MAX_RECENT) {
            recent.removeFirst();
        }
        recent.addLast(item);
    }

    private static NarrativeItem.Category categoryFor(NewsSource.Article a) {
        String text = ((a.headline() == null ? "" : a.headline()) + " "
                + (a.summary() == null ? "" : a.summary())).toLowerCase(Locale.ROOT);
        for (String w : EARNINGS_WORDS) {
            if (text.contains(w)) {
                return NarrativeItem.Category.EARNINGS;
            }
        }
        return NarrativeItem.Category.NEWS;
    }

    private static NarrativeItem.Sentiment sentiment(String headline, String summary) {
        String text = ((headline == null ? "" : headline) + " "
                + (summary == null ? "" : summary)).toLowerCase(Locale.ROOT);
        boolean bull = containsAny(text, BULLISH_WORDS);
        boolean bear = containsAny(text, BEARISH_WORDS);
        if (bull == bear) {
            return NarrativeItem.Sentiment.NEUTRAL; // none, or conflicting — stay neutral
        }
        return bull ? NarrativeItem.Sentiment.BULLISH : NarrativeItem.Sentiment.BEARISH;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** Stable positive hash for articles Finnhub sends without an id (dedupe fallback). */
    private static long stableHash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h == 0 ? 1 : Math.abs(h);
    }
}
