package io.jethro.app.discovery;

import io.jethro.app.risk.RefDataInstrumentRefSource;
import io.jethro.refdata.RefDataRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Production {@link UniverseRefdataGateway}: delegates writes/reads to the reference-data repository and,
 * after a change, refreshes the {@link RefDataInstrumentRefSource} cache so a promoted (monitor-only) or
 * evicted name is immediately visible to every reader — the mark path, risk, the order gate, the UI.
 */
public final class RefDataUniverseGateway implements UniverseRefdataGateway {

    private final RefDataRepository repository;
    private final RefDataInstrumentRefSource refSource;

    public RefDataUniverseGateway(RefDataRepository repository, RefDataInstrumentRefSource refSource) {
        this.repository = repository;
        this.refSource = refSource;
    }

    @Override
    public boolean exists(String instrumentId) {
        return repository.instrumentExists(instrumentId);
    }

    @Override
    public void writeMonitored(String instrumentId, String assetClass, String currency, BigDecimal multiplier,
                               Map<String, String> symbology, Map<String, String> attributes) {
        repository.writeMonitoredInstrument(instrumentId, assetClass, currency, multiplier, symbology, attributes);
    }

    @Override
    public List<String> discoveredInstrumentIds() {
        return repository.discoveredInstrumentIds();
    }

    @Override
    public boolean hasFills(String instrumentId) {
        return repository.hasFills(instrumentId);
    }

    @Override
    public boolean evict(String instrumentId) {
        return repository.evictDiscoveredInstrument(instrumentId);
    }

    @Override
    public void refresh() {
        refSource.refresh();
    }
}
