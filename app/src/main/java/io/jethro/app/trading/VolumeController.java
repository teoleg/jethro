package io.jethro.app.trading;

import io.jethro.app.order.MeasuredAdvSource;
import io.jethro.refdata.RefDataRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Live traded-volume / liquidity per instrument (ADR-0032, phase 1): the measured ADV that now
 * drives the execution participation cap and √-impact model, plus the relative-volume ratio a
 * volume-confirmed signal reads. Read-only; measured ADV is populated for the sim tape (a live
 * feed has no fixed prints/day and falls back to the configured ADV, shown as null here).
 */
@RestController
public final class VolumeController {

    public record VolumeDto(String instrumentId, String name, long samples,
                            Double measuredAdvUsd, double relativeVolume) {
    }

    private final ObjectProvider<TradingCoreLifecycle> tradingCore;
    private final ObjectProvider<MeasuredAdvSource> measuredAdv;
    private final ObjectProvider<RefDataRepository> refData;
    private final TradingCoreProperties properties;

    public VolumeController(ObjectProvider<TradingCoreLifecycle> tradingCore,
                            ObjectProvider<MeasuredAdvSource> measuredAdv,
                            ObjectProvider<RefDataRepository> refData,
                            TradingCoreProperties properties) {
        this.tradingCore = tradingCore;
        this.measuredAdv = measuredAdv;
        this.refData = refData;
        this.properties = properties;
    }

    @GetMapping("/api/volume")
    public List<VolumeDto> volume() {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        var stats = core != null ? core.volumeStats() : null;
        MeasuredAdvSource adv = measuredAdv.getIfAvailable();
        Map<String, String> names = instrumentNames();
        return properties.simInstruments().stream().map(id -> {
            long samples = stats != null ? stats.sampleCount(id) : 0;
            double rel = stats != null ? stats.relativeVolume(id) : 1.0;
            Double advUsd = adv != null ? adv.advUsd(id).map(java.math.BigDecimal::doubleValue).orElse(null) : null;
            return new VolumeDto(id, names.getOrDefault(id, id), samples, advUsd, rel);
        }).toList();
    }

    private Map<String, String> instrumentNames() {
        RefDataRepository rd = refData.getIfAvailable();
        return rd != null ? rd.instrumentAttribute("name") : Map.of();
    }
}
