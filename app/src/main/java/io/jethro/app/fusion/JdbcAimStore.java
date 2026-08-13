package io.jethro.app.fusion;

import io.jethro.messaging.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Postgres-backed {@link AimStore} over {@code fusion_aim} (V51, ADR-0140).
 *
 * <p>Scoped to the current {@link Provenance#mode() feed mode} (ADR-0029 / invariant 8): a sim→live
 * switch restores nothing from the other mode's stream. Quantities move as {@code BigDecimal} against
 * a {@code NUMERIC(20,6)} column — exact decimal at the boundary, no float anywhere (invariant 1).
 *
 * <p>Every operation is best-effort. A read failure returns nothing to restore (a cold aim path, i.e.
 * the pre-ADR-0140 behaviour); a write failure logs and leaves the in-memory aims in force for the
 * rest of the run. A broken database must never stop the desk from trading.
 */
public final class JdbcAimStore implements AimStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAimStore.class);

    /** Matches the planner's QTY_SCALE and the {@code numeric(20,6)} column. */
    private static final int QTY_SCALE = 6;

    private final JdbcTemplate jdbc;

    public JdbcAimStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String feedMode() {
        return Provenance.mode().name();
    }

    @Override
    public Map<String, BigDecimal> load() {
        try {
            Map<String, BigDecimal> out = new HashMap<>();
            jdbc.query("select instrument, aim from fusion_aim where feed_mode = ?",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        BigDecimal aim = rs.getBigDecimal("aim");
                        if (aim != null) {
                            out.put(rs.getString("instrument"), aim.setScale(QTY_SCALE, RoundingMode.HALF_EVEN));
                        }
                    },
                    feedMode());
            if (!out.isEmpty()) {
                log.info("ADR-0140: restored {} fusion aims for feedMode={}", out.size(), feedMode());
            }
            return out;
        } catch (Exception e) {
            log.warn("could not restore fusion aims ({}); the aim path starts cold this run", e.toString());
            return Map.of();
        }
    }

    @Override
    public void save(Map<String, BigDecimal> aims) {
        String mode = feedMode();
        try {
            if (aims.isEmpty()) {
                jdbc.update("delete from fusion_aim where feed_mode = ?", mode);
                return;
            }
            List<Object[]> rows = new ArrayList<>(aims.size());
            List<Object> keep = new ArrayList<>(aims.size() + 1);
            keep.add(mode);
            for (Map.Entry<String, BigDecimal> e : aims.entrySet()) {
                rows.add(new Object[] {mode, e.getKey(), e.getValue()});
                keep.add(e.getKey());
            }
            jdbc.batchUpdate("""
                    insert into fusion_aim (feed_mode, instrument, aim) values (?, ?, ?)
                    on conflict (feed_mode, instrument) do update
                        set aim = excluded.aim, updated_at = now()
                    """, rows);
            // A name the buffer has aged out leaves no intent behind in the store either.
            String placeholders = String.join(", ", java.util.Collections.nCopies(aims.size(), "?"));
            jdbc.update("delete from fusion_aim where feed_mode = ? and instrument not in (" + placeholders + ")",
                    keep.toArray());
        } catch (Exception e) {
            log.warn("could not persist fusion aims (in-memory aims still apply): {}", e.toString());
        }
    }
}
