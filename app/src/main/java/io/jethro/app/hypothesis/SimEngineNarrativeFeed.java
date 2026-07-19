package io.jethro.app.hypothesis;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.trading.algo.hypothesis.NarrativeItem;
import io.jethro.trading.marketdata.sim.SimNewsEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Surfaces the sim's OWN generated news (ADR-0034) to the hypothesis layer, so the model reads the
 * very headlines that moved the tape — closing the news→price→volume loop (a headline both reprices
 * the name and, here, reaches the model). Used in pure-sim mode when sim news is enabled; the
 * seedable {@link SimNarrativeFeed} fixtures and the real {@link FinnhubNarrativeFeed} are the other
 * two sources. Nothing here is a number into sizing/risk — the model reads text + a coarse sentiment
 * (invariant 1/7).
 */
public final class SimEngineNarrativeFeed implements NarrativeFeed {

    private final TradingCoreLifecycle tradingCore;
    private final InstrumentNameSource names;

    public SimEngineNarrativeFeed(TradingCoreLifecycle tradingCore, InstrumentNameSource names) {
        this.tradingCore = tradingCore;
        this.names = names != null ? names : InstrumentNameSource.NONE;
    }

    @Override
    public List<NarrativeItem> poll(String regime, List<String> singleNameInstruments, long nowMillis) {
        List<SimNewsEngine.SimNewsEvent> events = tradingCore.recentSimNews(); // oldest-first
        List<NarrativeItem> out = new ArrayList<>(events.size());
        for (SimNewsEngine.SimNewsEvent e : events) {
            NarrativeItem.Sentiment sentiment = e.sign() >= 0
                    ? NarrativeItem.Sentiment.BULLISH : NarrativeItem.Sentiment.BEARISH;
            out.add(new NarrativeItem(e.id(), nowMillis, NarrativeItem.Category.NEWS,
                    e.instrumentId(), sentiment, displayHeadline(e)));
        }
        return out; // events are oldest-first ⇒ newest last, per the contract
    }

    /** Swaps the leading internal id for the instrument's display name so the model reads a real
     *  name (e.g. "S&P 500 e-mini rallies …" rather than "ES rallies …"). */
    private String displayHeadline(SimNewsEngine.SimNewsEvent e) {
        String headline = e.headline();
        String name = names.displayName(e.instrumentId());
        if (name != null && !name.isBlank() && headline.startsWith(e.instrumentId())) {
            return name + headline.substring(e.instrumentId().length());
        }
        return headline;
    }
}
