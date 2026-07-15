package io.jethro.app.hypothesis;

import io.jethro.refdata.RefDataRepository;

import java.util.Map;

/**
 * Resolves an instrument's human {@code display_name} from reference data (V27) — the
 * source for names the narration model reads. Names live in refdata, never a hardcoded
 * per-name map in code (review GAP-4); an unknown instrument returns null and the caller
 * falls back to a generic asset-class phrase.
 */
public interface InstrumentNameSource {

    /** The refdata display name for an instrument, or null if it has none. */
    String displayName(String instrumentId);

    /** No refdata (persistence off): everything falls back to the generic phrase. */
    InstrumentNameSource NONE = id -> null;

    /** Snapshot of the {@code display_name} attribute at wiring time (names are static). */
    static InstrumentNameSource from(RefDataRepository refData) {
        Map<String, String> names = refData.instrumentAttribute("display_name");
        return names::get;
    }
}
