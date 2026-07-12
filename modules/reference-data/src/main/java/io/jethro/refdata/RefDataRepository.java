package io.jethro.refdata;

import io.jethro.domain.AssetClass;
import io.jethro.domain.Book;
import io.jethro.domain.BookId;
import io.jethro.domain.Instrument;
import io.jethro.domain.InstrumentId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC access to reference data. Exact decimals come back as BigDecimal from NUMERIC. */
public class RefDataRepository {

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
}
