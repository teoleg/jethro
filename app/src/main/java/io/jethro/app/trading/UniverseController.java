package io.jethro.app.trading;

import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * The tradable universe from the refdata master (invariant 9) — the single DYNAMIC source of what the
 * platform trades. Host-side tooling (notably {@code scripts/fetch_bars.py}, which fetches the daily
 * history the OOS backtest validates on) reads THIS rather than carrying its own hardcoded ticker list,
 * so a discovery-promoted (ADR-0060) or migration-seeded name is picked up automatically and can never be
 * silently missing history. Read-only, and it exposes only the id + asset class — no provider symbology
 * crosses the boundary (invariant 2); the consumer derives whatever provider symbol it needs.
 */
@RestController
public final class UniverseController {

    /** One tradable name: its internal id and asset class, from the master. */
    public record Name(String instrumentId, String assetClass) {
    }

    private final InstrumentRefSource refs;

    public UniverseController(InstrumentRefSource refs) {
        this.refs = refs;
    }

    @GetMapping("/api/universe")
    public List<Name> universe() {
        List<Name> out = new ArrayList<>();
        for (String id : refs.instrumentIds()) {
            String assetClass = refs.find(id).map(InstrumentRef::assetClass).orElse(null);
            out.add(new Name(id, assetClass));
        }
        out.sort((a, b) -> a.instrumentId().compareTo(b.instrumentId()));
        return out;
    }
}
