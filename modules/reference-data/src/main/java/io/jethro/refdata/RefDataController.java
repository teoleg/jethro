package io.jethro.refdata;

import io.jethro.domain.Instrument;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Reference-data REST surface (registered by the app assembly). */
@RestController
public class RefDataController {

    /** Multiplier as string: exact decimal at the boundary (invariant 1), never a JS float. */
    public record InstrumentDto(String instrumentId, String assetClass, String currency,
                                String contractMultiplier, Map<String, String> symbology) {
    }

    private final RefDataRepository repository;

    public RefDataController(RefDataRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/instruments")
    public List<InstrumentDto> instruments() {
        return repository.findAllInstruments().stream().map(RefDataController::toDto).toList();
    }

    @GetMapping("/api/books")
    public List<BookTree.Node> books() {
        return BookTree.assemble(repository.findAllBooks());
    }

    private static InstrumentDto toDto(Instrument instrument) {
        return new InstrumentDto(
                instrument.id().value(),
                instrument.assetClass().name(),
                instrument.currency(),
                instrument.contractMultiplier().toPlainString(),
                instrument.symbology());
    }
}
