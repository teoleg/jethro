// Shared price formatting so every page shows the same precision (prices are carried at 1e-6
// scale end to end — invariant 1 — but that raw precision must never leak to the screen).
// Rule: FX pairs keep FX precision (5 dp for majors, 3 for JPY crosses); rate/percent quotes
// get 4 dp; every other instrument — equities, index futures, Treasury futures — shows 2 dp.
(function (g) {
  function isFxPair(id) {
    return typeof id === "string" && /^[A-Z]{6}$/.test(id)
      && /(USD|EUR|GBP|JPY|AUD|CAD|CHF|NZD)$/.test(id);
  }
  function isRateQuote(id) {
    return typeof id === "string" && (id.indexOf("SOFR") >= 0 || id.indexOf("IRS") >= 0
      || id.indexOf("SWAP") >= 0 || id.indexOf(".TSY.") >= 0);
  }

  // Decimal places for an instrument's PRICE.
  g.priceDecimals = function (id) {
    if (isFxPair(id)) return id.indexOf("JPY") >= 0 ? 3 : 5;
    if (isRateQuote(id)) return 4;
    return 2;
  };

  // Format a price value (number or numeric string) for `id`. Non-numeric passes through.
  g.fmtPrice = function (value, id) {
    const n = Number(value);
    if (value === null || value === undefined || isNaN(n)) return value == null ? "—" : String(value);
    return n.toLocaleString(undefined, {
      minimumFractionDigits: g.priceDecimals(id),
      maximumFractionDigits: g.priceDecimals(id)
    });
  };
})(window);
