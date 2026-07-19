package io.jethro.app.backtest;

import io.jethro.domain.BookId;
import io.jethro.domain.Decimals;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Position;
import io.jethro.domain.Positions;
import io.jethro.domain.Side;
import io.jethro.trading.algo.strategy.MeanReversionStrategy;
import io.jethro.trading.algo.strategy.MomentumStrategy;
import io.jethro.trading.algo.strategy.Strategy;
import io.jethro.trading.algo.strategy.TradeSignal;
import io.jethro.trading.algo.strategy.VolatilityRegime;
import io.jethro.trading.marketdata.sim.SimTickGenerator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic backtest over the seedable sim tape (build step 8): replay ticks → strategy
 * → fills → <b>measured PnL</b>, with no human and no wall clock. This is the driver ADR-0019
 * named and the edge-measurement gate ADR-0022's bounded autonomy waits on.
 *
 * <p>Exactness (invariant 1 / finance-math): PnL runs through the same average-cost ledger as
 * the live book ({@link Positions#applyFill}); prices are scaled longs converted to
 * {@link BigDecimal} at the fill boundary; nothing uses {@code double} for money. The sizing/
 * long-only/position-cap rules mirror the live strategy so the backtest reflects real
 * behaviour. Costs (fees/slippage) are not modelled yet — noted, not hidden.
 */
public final class BacktestEngine {

    private static final BookId BOOK = new BookId("BT");
    private static final RoundingMode ROUND = RoundingMode.HALF_EVEN;
    // Calibrate a backtest tick to the live 100ms tick so vol matches production (ADR-0014).
    private static final double TICK_SECONDS = 0.1;
    private static final double TRADING_YEAR_SECONDS = 252 * 6.5 * 3_600;

    private static final class Book {
        Position position;
        BigDecimal realized = BigDecimal.ZERO; // ledger realized, net of costs below
        BigDecimal costs = BigDecimal.ZERO;
        int trades;
        Book(String instrumentId) {
            this.position = Position.flat(BOOK, new InstrumentId(instrumentId));
        }
    }

    public BacktestResult run(BacktestConfig cfg) {
        int n = cfg.instruments().size();
        long[] startPrices = new long[n];
        long[] maxSteps = new long[n];
        BigDecimal[] mult = new BigDecimal[n];
        String[] ids = new String[n];
        Map<String, Book> books = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            var instr = cfg.instruments().get(i);
            ids[i] = instr.instrumentId();
            mult[i] = instr.multiplier();
            startPrices[i] = Decimals.toScaledLong(instr.startPrice(), Decimals.PRICE_SCALE);
            maxSteps[i] = Math.max(1, Math.round(instr.annualVol()
                    * Math.sqrt(TICK_SECONDS / TRADING_YEAR_SECONDS) * Math.sqrt(3.0) * 1_000_000));
            books.put(ids[i], new Book(ids[i]));
        }

        SimTickGenerator gen = new SimTickGenerator(cfg.seed(), startPrices, maxSteps, cfg.regimes());
        // Price-derived risk-off sensing (ADR-0051), default params matching the live path for parity.
        VolatilityRegime volRegime = new VolatilityRegime();
        Strategy strategy = "mean-reversion".equals(cfg.algo())
                ? new MeanReversionStrategy(cfg.lookback(), cfg.thresholdSigmas(), cfg.minSignalBps())
                : new MomentumStrategy(cfg.lookback(), cfg.thresholdSigmas(), cfg.minSignalBps());

        BigDecimal[] mark = new BigDecimal[n];
        List<BigDecimal> equityCurve = new ArrayList<>();
        BigDecimal maxEquity = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        int evaluations = 0;
        int signalCount = 0;
        int totalTrades = 0;
        int closingFills = 0;
        int winningFills = 0;
        int sampleEvery = Math.max(1, cfg.ticks() / 200);

        for (int tick = 0; tick < cfg.ticks(); tick++) {
            for (int i = 0; i < n; i++) {
                mark[i] = Decimals.fromScaledLong(gen.nextPriceScaled(i), Decimals.PRICE_SCALE);
            }
            if (tick % cfg.evalEveryTicks() == 0) {
                evaluations++;
                List<Strategy.Observation> obs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    obs.add(new Strategy.Observation(ids[i], mark[i], false));
                }
                // ADR-0051: the risk-off scale is SENSED from prices (same detector as live), never the
                // sim's regime label — so the backtest measures the identical sizing behaviour the live
                // strategy runs. The sim tape may still switch regimes (cfg.regimes()); we detect the vol.
                volRegime.update(obs);
                BigDecimal regimeScale =
                        volRegime.regime() == VolatilityRegime.Regime.ELEVATED ? cfg.volatileScale() : BigDecimal.ONE;
                for (TradeSignal signal : strategy.evaluate(obs)) {
                    signalCount++;
                    int idx = indexOf(ids, signal.instrumentId());
                    if (idx < 0) {
                        continue;
                    }
                    BigDecimal qty = decideQuantity(cfg, signal, mark[idx], mult[idx],
                            books.get(ids[idx]).position, regimeScale);
                    if (qty == null) {
                        continue; // unsizeable, at position cap, or long-only-blocked short
                    }
                    Book book = books.get(ids[idx]);
                    Fill fill = new Fill("bt-" + tick + "-" + idx, "bt", BOOK,
                            new InstrumentId(ids[idx]), signal.side(), qty, mark[idx], Instant.EPOCH);
                    Positions.FillApplication applied = Positions.applyFill(book.position, fill, mult[idx]);
                    // Transaction cost: costBps of the traded notional (fees + slippage proxy),
                    // charged on every fill. Win/loss is judged on realized NET of this cost.
                    BigDecimal cost = qty.multiply(mark[idx]).multiply(mult[idx])
                            .multiply(cfg.costBps()).movePointLeft(4);
                    BigDecimal realizedNet = applied.realizedPnl().subtract(cost);
                    if (realizedNet.signum() != 0) {
                        closingFills++;
                        if (realizedNet.signum() > 0) {
                            winningFills++;
                        }
                    }
                    book.realized = book.realized.add(applied.realizedPnl());
                    book.costs = book.costs.add(cost);
                    book.position = applied.position();
                    book.trades++;
                    totalTrades++;
                }
            }
            BigDecimal equity = markToMarket(books, ids, mark, mult);
            maxEquity = equity.max(maxEquity);
            maxDrawdown = maxDrawdown.max(maxEquity.subtract(equity));
            if (tick % sampleEvery == 0) {
                equityCurve.add(scale(equity));
            }
        }

        BigDecimal realizedNetTotal = BigDecimal.ZERO;
        BigDecimal unrealizedTotal = BigDecimal.ZERO;
        BigDecimal costsTotal = BigDecimal.ZERO;
        List<BacktestResult.InstrumentResult> perInstrument = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Book book = books.get(ids[i]);
            BigDecimal unreal = unrealized(book.position, mark[i], mult[i]);
            BigDecimal realizedNet = book.realized.subtract(book.costs);
            realizedNetTotal = realizedNetTotal.add(realizedNet);
            unrealizedTotal = unrealizedTotal.add(unreal);
            costsTotal = costsTotal.add(book.costs);
            perInstrument.add(new BacktestResult.InstrumentResult(
                    ids[i], book.trades, scale(realizedNet), scale(unreal), book.position.quantity()));
        }
        double winRate = closingFills == 0 ? 0.0 : (double) winningFills / closingFills;
        return new BacktestResult(cfg.seed(), cfg.ticks(), evaluations, signalCount, totalTrades,
                scale(realizedNetTotal), scale(unrealizedTotal), scale(realizedNetTotal.add(unrealizedTotal)),
                scale(maxDrawdown), winRate, scale(costsTotal), perInstrument, equityCurve);
    }

    /** Live-mirroring sizing: target notional, per-order cap (never round up), long-only clamp,
     *  and the same-direction position cap. Returns null to skip. */
    private static BigDecimal decideQuantity(BacktestConfig cfg, TradeSignal signal, BigDecimal price,
                                             BigDecimal multiplier, Position held, BigDecimal regimeScale) {
        if (regimeScale.signum() == 0) {
            return null; // standing aside this regime
        }
        BigDecimal notionalPerUnit = price.multiply(multiplier);
        BigDecimal qty = cfg.targetNotional().multiply(regimeScale).divide(notionalPerUnit, 0, RoundingMode.DOWN);
        if (qty.signum() <= 0) {
            if (notionalPerUnit.compareTo(cfg.maxOrderNotional()) > 0) {
                return null; // one unit already exceeds the order cap — unsizeable
            }
            qty = BigDecimal.ONE;
        }
        BigDecimal heldQty = held.quantity();
        if (!cfg.allowShort() && signal.side() == Side.SELL) {
            if (heldQty.signum() <= 0) {
                return null; // nothing to reduce — would open a short
            }
            qty = qty.min(heldQty); // clamp to flat
        }
        boolean sameDirection = (heldQty.signum() > 0) == (signal.side() == Side.BUY);
        if (heldQty.signum() != 0 && sameDirection) {
            BigDecimal heldNotional = heldQty.abs().multiply(notionalPerUnit);
            if (heldNotional.compareTo(cfg.maxPositionNotional()) >= 0) {
                return null; // already at the position cap in this direction
            }
        }
        return qty;
    }

    private static BigDecimal markToMarket(Map<String, Book> books, String[] ids, BigDecimal[] mark, BigDecimal[] mult) {
        BigDecimal equity = BigDecimal.ZERO;
        for (int i = 0; i < ids.length; i++) {
            Book book = books.get(ids[i]);
            equity = equity.add(book.realized).subtract(book.costs)
                    .add(unrealized(book.position, mark[i], mult[i]));
        }
        return equity;
    }

    private static BigDecimal unrealized(Position pos, BigDecimal mark, BigDecimal multiplier) {
        if (pos.isFlat()) {
            return BigDecimal.ZERO;
        }
        return pos.quantity().multiply(mark.subtract(pos.avgCost())).multiply(multiplier);
    }

    private static int indexOf(String[] ids, String id) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(Decimals.PNL_SCALE, ROUND);
    }
}
