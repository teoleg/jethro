package io.jethro.order;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * TCA surface (ADR-0025): implementation-shortfall slippage vs arrival price, per fill and
 * aggregated per instrument. Positive bps = cost, negative = improvement; rate-quoted rows
 * (swaps) are basis points of RATE — different units, kept apart. Decimals as strings
 * (invariant 1). Empty when persistence is off.
 */
@RestController
public final class TcaController {

    public record RowDto(String orderId, String instrument, String side, String quantity,
                         String arrivalPrice, String fillPrice, String slippageBps,
                         boolean rateQuoted, String fee, long filledAtMillis) {
    }

    public record AggregateDto(String instrument, boolean rateQuoted, long fills,
                               String avgSlippageBps, String worstSlippageBps) {
    }

    public record TcaDto(List<AggregateDto> byInstrument, List<RowDto> recent) {
    }

    private final ObjectProvider<ExecutionQualityRepository> repository;

    public TcaController(ObjectProvider<ExecutionQualityRepository> repository) {
        this.repository = repository;
    }

    @GetMapping("/api/tca")
    public TcaDto tca(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        ExecutionQualityRepository repo = repository.getIfAvailable();
        if (repo == null) {
            return new TcaDto(List.of(), List.of());
        }
        List<AggregateDto> aggregates = repo.aggregates().stream()
                .map(a -> new AggregateDto(a.instrument(), a.rateQuoted(), a.fills(),
                        plain(a.avgSlippageBps()), plain(a.worstSlippageBps())))
                .toList();
        List<RowDto> recent = repo.recent(Math.min(Math.max(1, limit), 500)).stream()
                .map(r -> new RowDto(r.orderId(), r.instrument(), r.side(), plain(r.quantity()),
                        plain(r.arrivalPrice()), plain(r.fillPrice()), plain(r.slippageBps()),
                        r.rateQuoted(), plain(r.fee()), r.filledAt().toEpochMilli()))
                .toList();
        return new TcaDto(aggregates, recent);
    }

    private static String plain(BigDecimal v) {
        return v != null ? v.toPlainString() : null;
    }
}
