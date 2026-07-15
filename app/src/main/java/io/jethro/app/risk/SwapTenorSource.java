package io.jethro.app.risk;

import io.jethro.refdata.RefDataRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a swap instrument's tenor in whole years from reference data (the
 * {@code tenor_years} attribute, V27). The tenor is a contract term — it belongs in
 * refdata, not a hardcoded {@code instrumentId → years} map in code (review GAP-4; the map
 * was previously duplicated across SwapBookService, Dv01Service and the scenario wiring).
 * An instrument with no {@code tenor_years} row returns empty and the caller skips it
 * (never guesses a schedule — finance-math rule).
 */
public interface SwapTenorSource {

    /** The swap's tenor in whole years, or empty if refdata carries none. */
    Optional<Integer> tenorYears(String instrumentId);

    /** No refdata (persistence off): every swap is unknown, callers skip. */
    SwapTenorSource NONE = id -> Optional.empty();

    /** Snapshot of the {@code tenor_years} attribute at wiring time (contract terms are static). */
    static SwapTenorSource from(RefDataRepository refData) {
        Map<String, Integer> tenors = new HashMap<>();
        refData.instrumentAttribute("tenor_years").forEach((id, value) -> {
            try {
                tenors.put(id, Integer.valueOf(value.trim()));
            } catch (NumberFormatException ignored) {
                // A malformed tenor_years row is refdata's problem — skip it, never guess.
            }
        });
        return id -> Optional.ofNullable(tenors.get(id));
    }
}
