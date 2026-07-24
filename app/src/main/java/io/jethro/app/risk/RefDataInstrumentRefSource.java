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
    private volatile java.util.Set<String> monitorOnly = java.util.Set.of();

    public RefDataInstrumentRefSource(RefDataRepository repository) {
        this.repository = repository;
        reload();
    }

    /** Re-read reference data after a runtime write (ADR-0060 promotion/eviction) so new monitored
     *  names — and the monitor-only set — become visible to every reader without a restart. */
    public void refresh() {
        reload();
    }

    @Override
    public boolean monitorOnly(String instrumentId) {
        return monitorOnly.contains(instrumentId);
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

    @Override
    public java.util.Set<String> instrumentIds() {
        Map<String, InstrumentRef> current = cache;
        if (current.isEmpty()) {
            reload();
            current = cache;
        }
        return java.util.Set.copyOf(current.keySet());
    }

    private synchronized void reload() {
        Map<String, Map<String, String>> attributes = repository.findAllAttributes();
        Map<String, InstrumentRef> next = new HashMap<>();
        java.util.Set<String> monitored = new java.util.HashSet<>();
        for (Instrument i : repository.findAllInstruments()) {
            String id = i.id().value();
            Map<String, String> attrs = attributes.getOrDefault(id, Map.of());
            if (RefDataRepository.STATUS_MONITOR_ONLY.equals(attrs.get(RefDataRepository.ATTR_UNIVERSE_STATUS))) {
                monitored.add(id); // ADR-0060: marked but not tradable — the fusion order gate vetoes it
            }
            next.put(id, new InstrumentRef(id, i.assetClass().name(), i.currency(),
                    i.contractMultiplier(),
                    decimalAttribute(attrs, "mod_duration"),
                    decimalAttribute(attrs, "adv_usd"),
                    decimalAttribute(attrs, "notional_per_lot"),
                    decimalAttribute(attrs, "spread_bps"),
                    attrs.get("hedge_group"),
                    attrs.get("hedge_proxy"),
                    decimalAttribute(attrs, "hedge_beta")));
        }
        cache = next;
        monitorOnly = java.util.Set.copyOf(monitored);
    }

    /** A decimal instrument attribute (e.g. mod_duration for rates scenarios), or null. */
    private static java.math.BigDecimal decimalAttribute(Map<String, String> attributes, String name) {
        String value = attributes.get(name);
        if (value == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(value);
        } catch (NumberFormatException e) {
            return null; // malformed refdata never breaks valuation — sensitivity just absent
        }
    }
}
