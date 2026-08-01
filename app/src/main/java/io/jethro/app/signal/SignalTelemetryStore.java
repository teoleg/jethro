package io.jethro.app.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistence for ADR-0055 phase-1 signal telemetry. Records a source's directional call, resolves it
 * by the realised forward return at horizon, and serves rolling per-source stats. feed_mode-scoped
 * (ADR-0029): every read filters to the running {@link io.jethro.messaging.Provenance#mode()} so a
 * sim→live switch never mixes a mode's signal quality into another's. Best-effort — a failed write is
 * logged and dropped (telemetry never sits on any decision path).
 */
public final class SignalTelemetryStore {

    private static final Logger log = LoggerFactory.getLogger(SignalTelemetryStore.class);

    private final JdbcTemplate jdbc;

    public SignalTelemetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An unresolved observation awaiting its forward return. */
    public record Open(String id, String source, String instrument, int direction, BigDecimal entryMark) {
    }

    /**
     * True when this source already has an unresolved call open on this instrument <b>at this
     * horizon</b> (this mode). The horizon is part of the key (ADR-0082): the same call measured over
     * an hour and over four minutes is two observations of two different quantities, and holding only
     * one open per (source, instrument) would let the longest rung starve every shorter one.
     */
    public boolean hasOpen(String source, String instrument, int horizonSeconds) {
        try {
            Long n = jdbc.queryForObject("""
                    select count(*) from signal_observations
                    where source = ? and instrument = ? and horizon_seconds = ?
                      and resolved = false and feed_mode = ?
                    """, Long.class, source, instrument, horizonSeconds, mode());
            return n != null && n > 0;
        } catch (Exception e) {
            return true; // on doubt, don't record a possible duplicate
        }
    }

    public void record(String id, String source, String instrument, int direction, BigDecimal entryMark,
                       Instant entryAt, int horizonSeconds) {
        try {
            jdbc.update("""
                    insert into signal_observations
                        (id, source, instrument, direction, entry_mark, entry_at, horizon_seconds, feed_mode)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """, id, source, instrument, direction, entryMark, Timestamp.from(entryAt),
                    horizonSeconds, mode());
        } catch (Exception e) {
            log.debug("signal telemetry record failed ({}/{}) — dropped: {}", source, instrument, e.toString());
        }
    }

    /** Open observations whose horizon has elapsed by {@code now} (this mode). */
    public List<Open> due(Instant now) {
        try {
            return jdbc.query("""
                    select id, source, instrument, direction, entry_mark from signal_observations
                    where resolved = false and feed_mode = ?
                      and entry_at + (horizon_seconds * interval '1 second') <= ?
                    order by entry_at limit 500
                    """, (rs, i) -> new Open(rs.getString("id"), rs.getString("source"),
                    rs.getString("instrument"), rs.getInt("direction"), rs.getBigDecimal("entry_mark")),
                    mode(), Timestamp.from(now));
        } catch (Exception e) {
            log.debug("signal telemetry due() failed: {}", e.toString());
            return List.of();
        }
    }

    public void resolve(String id, BigDecimal exitMark, double realizedReturn, String outcome, Instant at) {
        try {
            jdbc.update("""
                    update signal_observations
                    set resolved = true, exit_mark = ?, realized_return = ?, outcome = ?, resolved_at = ?
                    where id = ? and resolved = false
                    """, exitMark, BigDecimal.valueOf(realizedReturn), outcome, Timestamp.from(at), id);
        } catch (Exception e) {
            log.debug("signal telemetry resolve({}) failed: {}", id, e.toString());
        }
    }

    /**
     * The most recent {@code cohortLimit} emission cohorts for one source at one horizon (this mode),
     * each already reduced to the sufficient statistics {@link SignalScoring} needs (ADR-0108).
     *
     * <p><b>Why the bound counts cohorts.</b> Since ADR-0077 the expectancy's standard error is
     * estimated ACROSS emission cohorts, so cohorts — not rows — are the estimator's sample. Bounding
     * the read at a row count therefore bounded the statistics in the wrong unit: a source that emits
     * its whole cross-section at once spends the budget at its own width, so a 23-name source was
     * pinned at ~22 independent draws forever while a one-name-at-a-time source got the full count.
     * The edge gate's power was capped by cross-section width instead of by accumulated evidence, and
     * no amount of running could lift it. Counting the bound in cohorts fixes that and — because each
     * cohort comes back as one row rather than its whole cross-section — reads LESS from the database
     * than the row-bounded query it replaces.
     *
     * <p>The bound is applied PER HORIZON deliberately (ADR-0082). A short rung resolves many times
     * more often than a long one — sixteen times, at a 4× ladder ratio two steps down — so a single
     * newest-first window across all rungs would fill with the fastest rung and silently starve the
     * slowest of the very history the desk has waited hours to accumulate.
     *
     * <p>Cohorts are cut by the same rule {@link SignalScoring#sweepCohortMeans} applies in memory
     * (ADR-0120): a name appears at most once per cohort, so the cohort index is the running maximum of
     * the per-name occurrence count over observations ordered by entry instant. Doing it in SQL is what
     * keeps the transfer constant; {@code SweepCohortTest} pins the arithmetic on the Java side. The
     * superseded rule cut a cohort wherever a CLOCK GAP exceeded {@code cohort-window-seconds}, which
     * measured how fast the scheduler walks the universe rather than how often the market was drawn —
     * on this book it split each pass into ~2.2 cohorts and inflated the gate's degrees of freedom.
     *
     * <p>The occurrence count is taken AFTER the bad-print predicate below, so an excluded observation
     * does not advance its name's position in the sweep — evidence removed from the estimator must not
     * silently re-cut the sample it is estimated from.
     *
     * <p>{@code flatThresholdBps} classifies each observation WIN/LOSS/FLAT exactly as
     * {@link SignalScoring#outcome(double, double)} does — a move counts only if it clears the
     * threshold — so the hit-rate is the same statistic wherever it is computed.
     *
     * <p><b>Bad prints are not evidence (ADR-0109).</b> An observation whose realised move exceeds the
     * desk's own corporate-action / bad-print threshold for that instrument's asset class
     * ({@code jethro.trading.mark-jump-bps}, via reference data) is excluded. The jump guard already
     * says such a price never reaches P&amp;L, orders, sizing or history; letting it reach the
     * expectancy — whose standard error is the denominator of every gate above it — is the same bad
     * print arriving by another door. See {@link #discardedCount} for the count that goes with it.
     */
    public List<SignalScoring.Cohort> resolvedCohorts(String source, int horizonSeconds, Instant since,
                                                      double flatThresholdBps,
                                                      int cohortLimit,
                                                      Map<String, Integer> badPrintBpsByAssetClass) {
        BigDecimal threshold = BigDecimal.valueOf(Math.abs(flatThresholdBps) / 1e4);
        String[] caps = capArrays(badPrintBpsByAssetClass);
        try {
            return jdbc.query("""
                    with caps as (
                        select cls, bps from unnest(string_to_array(?, ','),
                                                    string_to_array(?, ',')::int[]) as t(cls, bps)
                    ), resolved_obs as (
                        select o.entry_at, o.realized_return,
                               row_number() over (partition by o.instrument order by o.entry_at)
                                   as occurrence
                        from signal_observations o
                        left join instrument i on i.instrument_id = o.instrument
                        left join caps c on c.cls = i.asset_class
                        left join caps d on d.cls = 'DEFAULT'
                        where o.source = ? and o.horizon_seconds = ? and o.resolved = true
                          and o.feed_mode = ? and o.resolved_at >= ? and o.realized_return is not null
                          and not (coalesce(c.bps, d.bps, 0) > 0
                                   and abs(o.realized_return) >= coalesce(c.bps, d.bps) / 10000.0)
                    ), cohorted as (
                        select realized_return, entry_at,
                               max(occurrence) over (order by entry_at
                                   rows between unbounded preceding and current row) as cohort_id
                        from resolved_obs
                    )
                    select count(*) as n,
                           sum(realized_return) as sum_return,
                           sum(realized_return * realized_return) as sum_squared_return,
                           count(*) filter (where realized_return > ?) as wins,
                           count(*) filter (where realized_return < ?) as losses
                    from cohorted
                    group by cohort_id
                    order by max(entry_at) desc
                    limit ?
                    """, (rs, i) -> new SignalScoring.Cohort(
                            rs.getLong("n"),
                            rs.getBigDecimal("sum_return").doubleValue(),
                            rs.getBigDecimal("sum_squared_return").doubleValue(),
                            rs.getLong("wins"),
                            rs.getLong("losses")),
                    caps[0], caps[1], source, horizonSeconds, mode(),
                    Timestamp.from(since), threshold, threshold.negate(), cohortLimit);
        } catch (Exception e) {
            log.debug("cohort read failed for {}@{}s: {}", source, horizonSeconds, e.toString());
            return List.of();
        }
    }

    /**
     * How many resolved observations the bad-print exclusion kept out of one source's expectancy at one
     * horizon (ADR-0109) — the same predicate {@link #resolvedCohorts} applies, counted rather than
     * dropped in silence. Evidence removed from a gate that governs exposure is not a detail: it is
     * shown on {@code /api/signals/discards} and in the loop report, and a rising count is the desk
     * telling the operator that its mark stream, not its alpha, is what changed.
     */
    public long discardedCount(String source, int horizonSeconds, Instant since,
                               Map<String, Integer> badPrintBpsByAssetClass) {
        String[] caps = capArrays(badPrintBpsByAssetClass);
        try {
            Long n = jdbc.queryForObject("""
                    with caps as (
                        select cls, bps from unnest(string_to_array(?, ','),
                                                    string_to_array(?, ',')::int[]) as t(cls, bps)
                    )
                    select count(*)
                    from signal_observations o
                    left join instrument i on i.instrument_id = o.instrument
                    left join caps c on c.cls = i.asset_class
                    left join caps d on d.cls = 'DEFAULT'
                    where o.source = ? and o.horizon_seconds = ? and o.resolved = true
                      and o.feed_mode = ? and o.resolved_at >= ? and o.realized_return is not null
                      and coalesce(c.bps, d.bps, 0) > 0
                      and abs(o.realized_return) >= coalesce(c.bps, d.bps) / 10000.0
                    """, Long.class, caps[0], caps[1], source, horizonSeconds, mode(),
                    Timestamp.from(since));
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.debug("discard count failed for {}@{}s: {}", source, horizonSeconds, e.toString());
            return 0;
        }
    }

    /**
     * The per-asset-class bad-print thresholds as two parallel comma-joined lists, the shape the
     * queries above {@code unnest} into a join table. Fails OPEN: an absent or unusable map becomes a
     * single {@code DEFAULT=0} entry, and zero disables the exclusion for that class exactly as it
     * disables the jump guard itself — a mis-wired threshold must never be able to delete the desk's
     * evidence. Class keys are restricted to the reference-data alphabet, so nothing but a name can
     * reach the array literal.
     */
    static String[] capArrays(Map<String, Integer> badPrintBpsByAssetClass) {
        List<String> classes = new ArrayList<>();
        List<String> bps = new ArrayList<>();
        if (badPrintBpsByAssetClass != null) {
            for (var e : badPrintBpsByAssetClass.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || !ASSET_CLASS_KEY.matcher(e.getKey()).matches()) {
                    continue;
                }
                classes.add(e.getKey());
                bps.add(Integer.toString(Math.max(0, e.getValue())));
            }
        }
        if (classes.isEmpty()) {
            return new String[] {"DEFAULT", "0"};
        }
        return new String[] {String.join(",", classes), String.join(",", bps)};
    }

    private static final java.util.regex.Pattern ASSET_CLASS_KEY = java.util.regex.Pattern.compile("[A-Z_]{1,16}");

    /** Distinct sources seen (this mode) — resolved or open — so the view lists every live source. */
    public List<String> sources() {
        try {
            return jdbc.query("select distinct source from signal_observations where feed_mode = ? order by source",
                    (rs, i) -> rs.getString(1), mode());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Unresolved calls this source has open at {@code horizonSeconds} (this mode). */
    public long openCount(String source, int horizonSeconds) {
        try {
            Long n = jdbc.queryForObject("""
                    select count(*) from signal_observations
                    where source = ? and horizon_seconds = ? and resolved = false and feed_mode = ?
                    """, Long.class, source, horizonSeconds, mode());
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String mode() {
        return io.jethro.messaging.Provenance.mode().name();
    }
}
