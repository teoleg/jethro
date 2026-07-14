package io.jethro.app.hypothesis;

/**
 * One entry in the AI hypothesis <b>event ledger</b> (ADR-0022): a distinct thesis the model
 * proposed for an instrument, with the quant layer's verdict and whether it auto-executed.
 * The ledger is keyed on (instrument, thesis), so the SAME security with a DIFFERENT narrative
 * is a separate event that stays put — it isn't overwritten by the next cycle the way a
 * "current proposals" view was. A repeat of the same thesis updates the existing entry in place
 * (status/verdict) rather than piling up duplicates.
 */
public record HypothesisEvent(long timestampMillis, String instrumentId, String direction,
                              String conviction, String thesis, String verdict, boolean autoTraded,
                              Boolean backtestSupports, String backtestPnl, Integer backtestTrades,
                              String note) {
}
