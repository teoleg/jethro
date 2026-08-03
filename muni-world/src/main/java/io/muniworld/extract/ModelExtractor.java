package io.muniworld.extract;

import java.util.List;
import java.util.Map;

/**
 * The assisted-extraction SPI (ADR-0015 Phase 3, governed by ADR-0012): a model (local SLM via Ollama, or the
 * Claude frontier tier) proposes candidate term rows from OS text whose layout the deterministic parser
 * couldn't read. It is <b>advisory only</b> — every proposed number is verified against the source text by
 * {@link VerifyingAssistedExtractor} before it can land, so a model can help locate/structure but can never
 * assert a number that isn't in the document (jethro invariant 7 / ADR-0016, inherited).
 *
 * <p>No bean by default (extraction is deterministic-first); a real implementation is wired only where a model
 * is configured, so the sandbox/CI never calls a model.
 */
public interface ModelExtractor {

    /** Propose candidate rows (keyed like {@link OfficialStatementParser#FIELD_MAP}) from OS text. */
    List<Map<String, Object>> propose(String osText);
}
