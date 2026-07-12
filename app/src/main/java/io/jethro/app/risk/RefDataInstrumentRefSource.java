package io.jethro.app.risk;

import io.jethro.domain.Instrument;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Binds risk-pnl's {@link InstrumentRefSource} port to the reference-data module
 * (ADR-0015: cross-module access via a published port, not internals). Instruments are
 * static, so they're cached; a miss on an empty cache triggers one (re)load to cover
 * startup ordering against Flyway migration.
 */
public final class RefDataInstrumentRefSource implements InstrumentRefSource {

    private final RefDataRepository repository;
    private volatile Map<String, InstrumentRef> cache = Map.of();

    public RefDataInstrumentRefSource(RefDataRepository repository) {
        this.repository = repository;
        reload();
    }

    @Override
    public Optional<InstrumentRef> find(String instrumentId) {
        Map<String, InstrumentRef> current = cache;
        InstrumentRef ref = current.get(instrumentId);
        if (ref == null && current.isEmpty()) {
            reload();
            ref = cache.get(instrumentId);
        }
        return Optional.ofNullable(ref);
    }

    private synchronized void reload() {
        Map<String, InstrumentRef> next = new HashMap<>();
        for (Instrument i : repository.findAllInstruments()) {
            next.put(i.id().value(), new InstrumentRef(
                    i.id().value(), i.assetClass().name(), i.currency(), i.contractMultiplier()));
        }
        cache = next;
    }
}
