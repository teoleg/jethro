package io.jethro.refdata;

import io.jethro.domain.AssetClass;
import io.jethro.domain.Book;
import io.jethro.domain.BookId;
import io.jethro.domain.Instrument;
import io.jethro.domain.InstrumentId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC access to reference data. Exact decimals come back as BigDecimal from NUMERIC. */
public class RefDataRepository {

    /** Attribute name marking a runtime-added (ADR-0060) instrument's provenance. */
    public static final String ATTR_SOURCE = "source";
    /** Attribute value for a discovery-promoted instrument (ADR-0060) — never migration-seeded. A
     *  discovered name is a first-class, tradable instrument; this only records where it came from
     *  (and marks it as the evictable set). */
    public static final String SOURCE_DISCOVERED = "discovered";

    private final JdbcTemplate jdbc;

    public RefDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Book> findAllBooks() {
        return jdbc.query("select book_id, name, base_currency, parent_id from book order by book_id",
                (rs, i) -> new Book(
                        new BookId(rs.getString("book_id")),
                        rs.getString("name"),
                        rs.getString("base_currency"),
                        Optional.ofNullable(rs.getString("parent_id")).map(BookId::new)));
    }

    /** Product terms (tenor, index, day counts) per instrument — OTC definitions (V7). */
    public Map<String, Map<String, String>> findAllAttributes() {
        Map<String, Map<String, String>> attributes = new HashMap<>();
        jdbc.query("select instrument_id, name, value from instrument_attributes", rs -> {
            attributes.computeIfAbsent(rs.getString("instrument_id"), k -> new HashMap<>())
                    .put(rs.getString("name"), rs.getString("value"));
        });
        return attributes;
    }

    /** One attribute across all instruments: {@code instrumentId → value}. The refdata source
     *  for per-instrument data code must not hardcode (names, tenor, spec — review GAP-4). */
    public Map<String, String> instrumentAttribute(String name) {
        Map<String, String> out = new HashMap<>();
        jdbc.query("select instrument_id, value from instrument_attributes where name = ?",
                (rs, i) -> out.put(rs.getString("instrument_id"), rs.getString("value")), name);
        return out;
    }

    public List<Instrument> findAllInstruments() {
        Map<String, Map<String, String>> symbology = new HashMap<>();
        jdbc.query("select instrument_id, source, symbol from instrument_symbology", rs -> {
            symbology.computeIfAbsent(rs.getString("instrument_id"), k -> new HashMap<>())
                    .put(rs.getString("source"), rs.getString("symbol"));
        });
        return jdbc.query(
                "select instrument_id, asset_class, currency, contract_multiplier from instrument order by instrument_id",
                (rs, i) -> new Instrument(
                        new InstrumentId(rs.getString("instrument_id")),
                        AssetClass.valueOf(rs.getString("asset_class")),
                        rs.getString("currency"),
                        rs.getBigDecimal("contract_multiplier"),
                        symbology.getOrDefault(rs.getString("instrument_id"), Map.of())));
    }

    // --- Runtime write path (ADR-0060): promotion of discovery candidates into the instrument master. ---

    public boolean instrumentExists(String instrumentId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from instrument where instrument_id = ?", Integer.class, instrumentId);
        return n != null && n > 0;
    }

    /**
     * Idempotently write a discovery-promoted instrument into the master (ADR-0060 §2): the base row, its
     * feed symbology, display name, and PROVISIONAL adv/spread (flagged — never a silent default). All
     * inserts are conflict-safe so a re-promotion (e.g. after a restart) is a no-op, not a crash. The
     * caller (the promotion service) has already confirmed the name is not core and clears the gate.
     */
    public void writeMonitoredInstrument(String instrumentId, String assetClass, String currency,
                                         BigDecimal multiplier, Map<String, String> symbology,
                                         Map<String, String> attributes) {
        jdbc.update("insert into instrument (instrument_id, asset_class, currency, contract_multiplier) "
                        + "values (?, ?, ?, ?) on conflict (instrument_id) do nothing",
                instrumentId, assetClass, currency, multiplier);
        symbology.forEach((source, symbol) -> jdbc.update(
                "insert into instrument_symbology (instrument_id, source, symbol) values (?, ?, ?) "
                        + "on conflict (instrument_id, source) do nothing",
                instrumentId, source, symbol));
        attributes.forEach((name, value) -> jdbc.update(
                "insert into instrument_attributes (instrument_id, name, value) values (?, ?, ?) "
                        + "on conflict (instrument_id, name) do update set value = excluded.value",
                instrumentId, name, value));
    }

    /** InstrumentIds written by the ADR-0060 runtime path (source=discovered) — the evictable set. */
    public List<String> discoveredInstrumentIds() {
        return jdbc.queryForList(
                "select instrument_id from instrument_attributes where name = ? and value = ? order by instrument_id",
                String.class, ATTR_SOURCE, SOURCE_DISCOVERED);
    }

    /** True if the instrument carries the discovered provenance — the ONLY names eviction may remove
     *  (a migration-seeded core name has no such row and is protected by construction, ADR-0060 §2). */
    public boolean isDiscovered(String instrumentId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from instrument_attributes where instrument_id = ? and name = ? and value = ?",
                Integer.class, instrumentId, ATTR_SOURCE, SOURCE_DISCOVERED);
        return n != null && n > 0;
    }

    /** Whether any fill exists for the instrument — eviction guard (never evict a name with a position/tape). */
    public boolean hasFills(String instrumentId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from fills where instrument_id = ?", Integer.class, instrumentId);
        return n != null && n > 0;
    }

    /**
     * Remove a discovered instrument and its attribute/symbology rows (ADR-0060 §4 eviction). Refuses to
     * touch a name that is NOT discovered — a core name can never be evicted through this path. Ordered
     * deletes respect the FK from symbology/attributes → instrument.
     */
    public boolean evictDiscoveredInstrument(String instrumentId) {
        if (!isDiscovered(instrumentId)) {
            return false; // core / unknown — never evict through the discovery path
        }
        jdbc.update("delete from instrument_attributes where instrument_id = ?", instrumentId);
        jdbc.update("delete from instrument_symbology where instrument_id = ?", instrumentId);
        jdbc.update("delete from instrument where instrument_id = ?", instrumentId);
        return true;
    }
}
