# Live-feed trading post-mortem — Alpaca session, 2026-07-24

**Author:** quant/trading review (senior-desk lens)
**Data:** one live session on the Alpaca IEX feed (`system-report` bundle, generated 2026-07-24 18:15).
**Verdict in one line:** the book is **losing money on its own trades and only *looks* flat because a
directional hedge happened to win on an up day.** Both deterministic signals have **negative measured
edge on the live tape**, and the system is **massively over-trading** — the two conditions that, per the
Fundamental Law of Active Management, *guarantee* value destruction. Do **not** treat the ≈-$263 headline
as "roughly break-even."

---

## 1. What the numbers actually say

### 1.1 P&L is a hedge artifact, not skill

| Book | Realized | Unrealized | Clean total | Gross | Net |
|---|---:|---:|---:|---:|---:|
| **ALPHA** (strategy) | −8,835 | −10 | **−8,845** | 100,109 | −23,379 |
| **HEDGE** (long ES) | +7,551 | −22 | **+7,529** | 23,403 | +23,403 |
| **MACRO** | +1,080 | −26 | +1,054 | 10,033 | −10,033 |
| **Firm** | −204 | −59 | **−263** | 133,545 | −10,008 |

The strategy book **bled −$8.8k**; the ES hedge (long, +$7.5k) offset it *because the market rose*. The
firm-level −$263 is the residual of two large, opposite numbers. On a **down** day the sign of the hedge
flips and the −$8.8k strategy bleed is exposed. This is textbook performance-attribution hygiene: judge a
hedged book by **alpha net of cost**, not the headline P&L (Grinold & Kahn, *Active Portfolio Management*,
2000, ch. 17 on performance analysis). By that measure the alpha book is deeply negative.

### 1.2 The signals have *negative* live edge

From `signals_telemetry` (scored on realised forward returns, this feed):

| Signal | Resolved | Hit rate | **Avg return / signal** |
|---|---:|---:|---:|
| mean-reversion | 136 | 0.508 | **−21.8 bps** |
| momentum | 117 | 0.483 | **−104.6 bps** |
| social | 6 | 0.60 | +1003 bps (n=6 — not significant) |

Both deterministic engines have **negative expectancy per signal**. A ~50% hit rate with a *negative*
average return is the signature of an edge that is, at best, zero and is being **eaten by costs and
adverse selection** (see §1.3–1.4).

### 1.3 The book is over-trading by ~2 orders of magnitude

- **921 fills on 2026-07-23** alone (fees $770); 1,204 filled / 91 rejected across the run.
- On a book of ~$100k gross, ~900 fills/day at ~$10–20k notional each is **~100× daily turnover**.
- Concentrated realised losses from round-tripping single names: **JPM −$5,077**, **GOOG −$1,952**,
  **MSFT −$1,499** realized — while their *unrealized* is near zero (JPM −$19). That is not a directional
  mark loss; it is **churn**: entering and exiting the same name repeatedly at a loss.

### 1.4 The mechanism: mean-reversion fading a trending tape

The selector put **mean-reversion on almost every name** (`Strategy selection`: AAPL, JNJ, NVDA, JPM,
GBPUSD, TSLA), and the book is **net short** the rallying names (JNJ −72, JPM −51, NVDA −81, MSFT −21). A
contrarian rule in an up-trend keeps **shorting strength, covering higher, re-shorting** — the JPM −$5k
realized is exactly this death-spiral on one name.

---

## 2. Root causes, each corroborated by the literature

### A. Negative IC × high breadth = engineered losses (the single most important point)

The **Fundamental Law of Active Management** (Grinold, "The Fundamental Law of Active Management," *JPM*
1989; Grinold & Kahn 2000) states the information ratio is
$$\mathrm{IR} \approx \mathrm{IC}\cdot\sqrt{\mathrm{BR}}$$
— skill (information coefficient) times the square root of breadth (independent bets). **When IC is
negative, adding breadth scales the *loss*.** This book is doing precisely that: a negative-edge signal
(§1.2) executed across ~900 bets/day (§1.3). More trading here is not diversification; it is leverage on a
negative edge. **The first-order fix is to stop trading names/algos whose *live* IC is not reliably
positive**, not to re-tune sizing.

### B. Turnover is destroying whatever edge might exist

- Gârleanu & Pedersen, "Dynamic Trading with Predictable Returns and Transaction Costs," *Journal of
  Finance* 2013: the optimal policy is **not** to trade to the target each period but to **"aim in front
  of the target" and trade only a fraction toward it**, with a **no-trade region** whose width scales with
  cost and inversely with signal persistence. A 30-second re-route to a fresh target (this fusion loop)
  with a thin band is the opposite of that result and mechanically over-trades.
- Novy-Marx & Velikov, "A Taxonomy of Anomalies and Their Trading Costs," *RFS* 2016: **high-turnover
  strategies are exactly the ones whose paper edge disappears after costs.** Frazzini, Israel & Moskowitz,
  "Trading Costs" (2018) show real costs scale with participation and turnover.
- Sharpe, "The Arithmetic of Active Management," *FAJ* 1991: costs are a deterministic drag; the active
  game is negative-sum after them.
- Almgren & Chriss, "Optimal Execution of Portfolio Transactions," *J. Risk* 2000: even *necessary* trades
  should be sliced to balance impact vs. risk — here trades are **unnecessary** (negative edge) *and*
  frequent, the worst quadrant.

### C. Short-horizon mean-reversion is largely microstructure, not alpha

- Lo & MacKinlay, "When Are Contrarian Profits Due to Stock Market Overreaction?," *RFS* 1990, and
  Lehmann, "Fads, Martingales, and Market Efficiency," *QJE* 1990: a large part of short-horizon reversal
  profit is **bid-ask bounce and lead-lag microstructure**, which **evaporates once you cross the spread**
  — which this book does ~900×/day (TCA shows 2–5 bps slippage per equity fill).
- De Bondt & Thaler (1985) reversal is a **multi-year** phenomenon; Jegadeesh & Titman, "Returns to Buying
  Winners and Selling Losers," *JF* 1993, show **3–12 month momentum**. Intraday contrarian trading is
  neither, and fighting a trend is the documented failure mode.

### D. The sim backtest overstated the edge (overfitting / sim→live gap)

The OOS selector's **sim** medians for mean-reversion were *positive* (JNJ +56, NVDA +213, TSLA +67) yet
the **live** average is **−21.8 bps**. That gap is the canonical backtest-overfitting signature:

- Bailey, Borwein, López de Prado & Zhu, "The Probability of Backtest Overfitting," *J. Comp. Finance*
  2017: selecting the best rule on in-sample (here, sim) paths inflates expected performance; out-of-sample
  (here, live) reverts toward — or below — zero.
- López de Prado, *Advances in Financial Machine Learning* 2018: use the **Deflated Sharpe Ratio** and
  **purged/embargoed CV**; a single-market seedable simulator is an in-sample device, not evidence of live
  edge.
- Harvey, Liu & Zhu, "…and the Cross-Section of Expected Returns," *RFS* 2016: with multiple strategies ×
  names × seeds tested, the significance hurdle must be raised (t ≳ 3), not the default.
- Lo, "The Adaptive Markets Hypothesis," *JPM* 2004: edges are **regime-** and **time-varying**; a rule
  validated on synthetic dynamics should be gated on *live* performance before it sizes real risk.

**Implication:** the OOS-on-sim gate (ADR-0049) is necessary but **not sufficient**. Routing must also be
gated on **live, cost-aware** signal performance.

### E. The regime detector reads ER=0 everywhere — **unconfirmed** (snapshot was post-close)

`efficiency_ratio = 0` for **every** instrument, `volRatio = 0.0`, regime = CHOP universally. That *looks*
like a stuck detector (Kaufman's Efficiency Ratio = |net| / Σ|steps| ∈ (0,1]; Kaufman, *Trading Systems
and Methods*, 5th ed. 2013). **Caveat, important:** this bundle was generated at **18:14 ET, ~2 h after the
16:00 close**, so equity marks are frozen at their closing prints (provider timestamps ~2 h old) and a
flat window trivially yields ER=0 — which is *expected*, not necessarily a bug. **We cannot conclude the
detector is broken from a post-close snapshot.** Action: capture an **intraday** (market-hours) report; the
`Strategy selection` sheet already carries per-name ER, so an intraday capture will show whether ER is
genuinely stuck at 0 while prices move (a real bug) or was just the after-hours freeze. Only then fix.

Related, and worth noting regardless: the free **Alpaca IEX tier carries ~2–3% of consolidated volume**, so
individual names print sparsely intraday — a thin, gappy series that both depresses ER and invites
adverse-selection churn (Lo & MacKinlay 1990). Whether that materially bit *this* session also needs the
intraday capture to confirm.

### F. Data-quality contamination

`SAP` (mark 0, `has_mark=N` — SAP.DE not returning), and rates futures `ZN/ZF/ZB` (mark 0) carry positions
with no live mark. Feeding un-marked names into signals/risk biases both. (Already tracked in the deferred
register; flagging because it pollutes any live edge estimate.)

---

## 3. Recommendations (prioritised; each grounded above)

1. **Stop routing on negative live edge.** Gate fusion routing on a **live, cost-adjusted** per-signal
   IC/expectancy that must be **positive with a real significance margin**, not just a positive *sim* OOS
   median. If nothing clears it, the correct position is **flat**. *(Grinold 1989; Harvey-Liu-Zhu 2016;
   López de Prado 2018.)* — biggest lever.
2. **Cut turnover hard.** Implement the Gârleanu-Pedersen policy properly: trade a **fraction** toward a
   **smoothed** target inside a **wide no-trade band** sized to cost; lengthen the fusion cadence (30s →
   minutes) and raise the conviction floor. *(Gârleanu-Pedersen 2013; Novy-Marx-Velikov 2016.)*
3. **Fix the Efficiency-Ratio / regime detector.** Restore a real ER so momentum can be selected in trends;
   without it the selector is structurally contrarian. *(Kaufman 2013.)* — an engineering bug.
4. **Do not trust sim edge for live sizing.** Add a live paper-trading probation with Deflated-Sharpe /
   multiple-testing-aware acceptance before a name/algo sizes risk. *(Bailey-López de Prado 2017.)*
5. **Report alpha net of cost, separately from the hedge.** Surface strategy-book alpha and cumulative
   transaction cost on the UI so a hedge can never again mask a bleeding book. *(Grinold-Kahn 2000.)*
6. **Clean the mark inputs** (SAP.DE symbology, rates-futures live marks) before drawing edge conclusions.

**Sequencing:** 5 and 3 are cheap/observability + a bug fix. 1 and 2 are the money items and are
**policy** decisions — they change what the platform trades and how often, so they are yours to direct,
not mine to impose.

---

## 4. Discovery / promotion (separate track)

- Candidates cleared the owner rule (BRK.B 466/152, GS 394/128, NFLX 383/126 — all score>250 & mentions>50,
  all feed-covered). Promotion is blunted by two things, **not** the gate logic: `max-promotions-per-day=2`,
  and a promoted name **does not enter the traded universe until the next restart** (feed symbol maps are
  read at boot — the registered "hot-subscribe" item). The `/api/universe/proposals` **ClassCastException**
  (NUMERIC→Double) that was crashing the Discover view is fixed.
- Note: promoting *more* names into a **negative-edge** engine (§2A) would **increase** losses, not
  diversify them. Fix the edge/turnover problem **before** widening the universe.

---

### References
- Grinold, R. (1989). *The Fundamental Law of Active Management.* Journal of Portfolio Management.
- Grinold, R. & Kahn, R. (2000). *Active Portfolio Management*, 2nd ed. McGraw-Hill.
- Gârleanu, N. & Pedersen, L. (2013). *Dynamic Trading with Predictable Returns and Transaction Costs.* Journal of Finance.
- Almgren, R. & Chriss, N. (2000). *Optimal Execution of Portfolio Transactions.* Journal of Risk.
- Jegadeesh, N. & Titman, S. (1993). *Returns to Buying Winners and Selling Losers.* Journal of Finance.
- De Bondt, W. & Thaler, R. (1985). *Does the Stock Market Overreact?* Journal of Finance.
- Lo, A. & MacKinlay, C. (1990). *When Are Contrarian Profits Due to Stock Market Overreaction?* Review of Financial Studies.
- Lehmann, B. (1990). *Fads, Martingales, and Market Efficiency.* Quarterly Journal of Economics.
- Kaufman, P. (2013). *Trading Systems and Methods*, 5th ed. Wiley.
- Lo, A. (2004). *The Adaptive Markets Hypothesis.* Journal of Portfolio Management.
- Bailey, D., Borwein, J., López de Prado, M. & Zhu, Q. (2017). *The Probability of Backtest Overfitting.* Journal of Computational Finance.
- López de Prado, M. (2018). *Advances in Financial Machine Learning.* Wiley.
- Harvey, C., Liu, Y. & Zhu, H. (2016). *…and the Cross-Section of Expected Returns.* Review of Financial Studies.
- Novy-Marx, R. & Velikov, M. (2016). *A Taxonomy of Anomalies and Their Trading Costs.* Review of Financial Studies.
- Frazzini, A., Israel, R. & Moskowitz, T. (2018). *Trading Costs.* Working paper, AQR.
- Sharpe, W. (1991). *The Arithmetic of Active Management.* Financial Analysts Journal.
