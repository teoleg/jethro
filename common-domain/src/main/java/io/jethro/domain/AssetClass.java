package io.jethro.domain;

/** Multi-asset support per ADR-0008; equities first, others as they arrive. */
public enum AssetClass {
    EQUITY,
    FUTURE,
    OPTION,
    FX,
    BOND,
    /** OTC interest-rate swaps — definitions only until Strata pricing lands (ADR-0020). */
    SWAP
}
