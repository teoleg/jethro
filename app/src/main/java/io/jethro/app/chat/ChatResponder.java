package io.jethro.app.chat;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.LimitBreach;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.RiskLimitEvaluator;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Deterministic answer handlers for the operational chat (ADR-0021). Every number here
 * comes from the risk projection / limits / attention feed — never from a model
 * (invariant 7). Answers are short and factual, and echo the understood intent so a
 * misclassification is obvious.
 */
public final class ChatResponder {

    private final RiskProjection risk;
    private final RiskLimitSource limits;
    private final RiskLimitEvaluator evaluator;
    private final AttentionFeed feed;

    public ChatResponder(RiskProjection risk, RiskLimitSource limits,
                         RiskLimitEvaluator evaluator, AttentionFeed feed) {
        this.risk = risk;
        this.limits = limits;
        this.evaluator = evaluator;
        this.feed = feed;
    }

    public String answer(ChatIntent intent) {
        ConsolidatedRisk snap = risk.snapshot(System.currentTimeMillis());
        return switch (intent.kind()) {
            case PNL -> pnl(snap, intent.book());
            case EXPOSURE -> exposure(snap, intent.book(), intent.instrument());
            case POSITIONS -> positions(snap, intent.book(), intent.instrument());
            case LIMITS -> limitStatus(snap);
            case SIGNALS -> signals();
            case HELP -> help();
        };
    }

    private String pnl(ConsolidatedRisk snap, String book) {
        if (book != null) {
            return snap.byBook().stream().filter(g -> g.key().equals(book)).findFirst()
                    .map(g -> "PnL — " + book + ": total " + money(g.totalPnl())
                            + " (realized " + money(g.realizedPnl()) + ", unrealized " + money(g.unrealizedPnl()) + ")")
                    .orElse("No activity in book " + book + " yet.");
        }
        var t = snap.total();
        return "PnL — firm: total " + money(t.totalPnl())
                + " (realized " + money(t.realizedPnl()) + ", unrealized " + money(t.unrealizedPnl()) + ")";
    }

    private String exposure(ConsolidatedRisk snap, String book, String instrument) {
        if (instrument != null) {
            List<PositionRisk> ps = snap.positions().stream()
                    .filter(p -> p.instrumentId().equals(instrument)).toList();
            if (ps.isEmpty()) {
                return "No exposure to " + instrument + " — flat.";
            }
            BigDecimal net = sum(ps, PositionRisk::netExposure);
            BigDecimal gross = sum(ps, PositionRisk::grossExposure);
            return "Exposure — " + instrument + ": net " + money(net) + ", gross " + money(gross)
                    + " across " + ps.size() + " book(s).";
        }
        if (book != null) {
            return snap.byBook().stream().filter(g -> g.key().equals(book)).findFirst()
                    .map(g -> "Exposure — " + book + ": net " + money(g.netExposure()) + ", gross " + money(g.grossExposure()))
                    .orElse("No positions in book " + book + ".");
        }
        var t = snap.total();
        return "Exposure — firm: net " + money(t.netExposure()) + ", gross " + money(t.grossExposure());
    }

    private String positions(ConsolidatedRisk snap, String book, String instrument) {
        List<PositionRisk> ps = snap.positions().stream()
                .filter(p -> book == null || p.bookId().equals(book))
                .filter(p -> instrument == null || p.instrumentId().equals(instrument))
                .filter(p -> p.quantity().signum() != 0)
                .toList();
        if (ps.isEmpty()) {
            return "No open positions" + (book != null ? " in " + book : "")
                    + (instrument != null ? " for " + instrument : "") + ".";
        }
        String list = ps.stream().limit(8)
                .map(p -> p.instrumentId() + " " + plain(p.quantity())
                        + (p.hasMark() ? " @ " + plain(p.mark()) : "") + " (" + p.bookId() + ")")
                .collect(Collectors.joining("; "));
        String more = ps.size() > 8 ? " …+" + (ps.size() - 8) + " more" : "";
        return ps.size() + " position(s): " + list + more;
    }

    private String limitStatus(ConsolidatedRisk snap) {
        List<LimitBreach> breaches = evaluator.evaluate(snap, limits);
        if (breaches.isEmpty()) {
            return "All books within their risk limits.";
        }
        String list = breaches.stream().limit(6)
                .map(b -> b.bookId() + " " + metric(b.metric()) + " " + money(b.actual())
                        + " vs limit " + money(b.limit()) + " (" + b.severity() + ")")
                .collect(Collectors.joining("; "));
        return breaches.size() + " limit issue(s): " + list;
    }

    private String signals() {
        List<AttentionFeed.AttentionItem> items = feed.snapshot().stream()
                .filter(i -> i.kind().startsWith("strategy")).toList();
        var throttled = items.stream().filter(i -> i.kind().equals("strategy-throttled")).findFirst();
        if (throttled.isPresent()) {
            return "Strategy throttled: " + throttled.get().title() + ".";
        }
        List<AttentionFeed.AttentionItem> signals = items.stream()
                .filter(i -> i.kind().equals("strategy-signal")).toList();
        if (signals.isEmpty()) {
            return "No active strategy signals right now.";
        }
        return signals.size() + " active signal(s). Latest: " + signals.get(0).title() + ".";
    }

    private String help() {
        return "I can answer about PnL, exposure/risk, positions, limits, and signals. "
                + "Try: \"pnl alpha\", \"exposure AAPL\", \"positions in MACRO\", \"any limits breached?\", \"what is the strategy doing?\"";
    }

    private static BigDecimal sum(List<PositionRisk> ps, java.util.function.Function<PositionRisk, BigDecimal> f) {
        return ps.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String metric(LimitBreach.Metric m) {
        return switch (m) {
            case GROSS_EXPOSURE -> "gross exposure";
            case NET_EXPOSURE -> "net exposure";
            case LOSS -> "loss";
            case INSTRUMENT_EXPOSURE -> "concentration";
        };
    }

    private static String money(BigDecimal v) {
        String num = new DecimalFormat("#,##0.00").format(v.abs());
        return (v.signum() < 0 ? "-$" : "$") + num;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
