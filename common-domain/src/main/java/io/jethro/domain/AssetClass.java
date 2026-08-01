package io.jethro.domain;

/** Multi-asset support per ADR-0008; equities first, others as they arrive. */
public enum AssetClass {
    EQUITY,
    FUTURE,
    OPTION,
    FX,
    BOND,
    /** OTC interest-rate swaps — definitions only until Strata pricing lands (ADR-0020). */
    SWAP,
    /** Spot market index (S&P 500, DAX, Nikkei, …) — a market-trend/context reference, NOT tradable
     *  spot (its future is the tradable expression). Marked for the trend feed; gated out of the order
     *  path everywhere (ADR-0129). */
    INDEX
}
