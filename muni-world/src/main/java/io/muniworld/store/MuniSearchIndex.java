package io.muniworld.store;

import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.KeyRange;
import org.lmdbjava.Txn;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static io.muniworld.store.MuniKeys.ascii;
import static io.muniworld.store.MuniKeys.bytes;
import static io.muniworld.store.MuniKeys.concat;
import static io.muniworld.store.MuniKeys.dateKey;
import static io.muniworld.store.MuniKeys.direct;
import static io.muniworld.store.MuniKeys.startsWith;
import static io.muniworld.store.MuniKeys.u64be;

/**
 * The ADR-0013 LMDB B-tree search index — a DERIVED, ordered-key layer over the canonical Postgres data,
 * built for the prefix and range scans the analysis/UI need. Four named B-trees in the shared env:
 *
 * <ul>
 *   <li><b>{@code sec}</b> — CUSIP-9 → value blob. Point lookup, and (since CUSIP-6 is the prefix of its
 *       CUSIP-9s) an issuer scan by prefix — one tree, two access patterns.</li>
 *   <li><b>{@code idx_maturity}</b> — {@code dateKey||cusip} → ∅. Range scan by maturity.</li>
 *   <li><b>{@code idx_coupon}</b> — {@code coupon||cusip} → ∅. Range scan by coupon.</li>
 *   <li><b>{@code idx_geo}</b> — {@code fips||cusip} → ∅. Prefix scan by state/county.</li>
 * </ul>
 *
 * Rebuildable from Postgres (losing it costs a reindex, never data). One writer, many concurrent readers.
 */
@Component
public final class MuniSearchIndex {

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] CUSIP_MAX = new byte[] {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}; // 9 × 0xFF — upper bound for a composite key

    private final Env<ByteBuffer> env;
    private final Dbi<ByteBuffer> sec;
    private final Dbi<ByteBuffer> byMaturity;
    private final Dbi<ByteBuffer> byCoupon;
    private final Dbi<ByteBuffer> byGeo;

    public MuniSearchIndex(MuniLmdbStore store) {
        this.env = store.env();
        this.sec = env.openDbi("sec", DbiFlags.MDB_CREATE);
        this.byMaturity = env.openDbi("idx_maturity", DbiFlags.MDB_CREATE);
        this.byCoupon = env.openDbi("idx_coupon", DbiFlags.MDB_CREATE);
        this.byGeo = env.openDbi("idx_geo", DbiFlags.MDB_CREATE);
    }

    /** Index one security into all trees in a single write transaction (idempotent — same key overwrites). */
    public void indexSecurity(String cusip, LocalDate maturity, long couponScaled, String geoFips, byte[] value) {
        byte[] c = ascii(cusip);
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            sec.put(txn, direct(c), direct(value == null ? EMPTY : value));
            if (maturity != null) {
                byMaturity.put(txn, direct(concat(dateKey(maturity), c)), direct(EMPTY));
            }
            if (couponScaled >= 0) {
                byCoupon.put(txn, direct(concat(u64be(couponScaled), c)), direct(EMPTY));
            }
            if (geoFips != null && !geoFips.isEmpty()) {
                byGeo.put(txn, direct(concat(ascii(geoFips), c)), direct(EMPTY));
            }
            txn.commit();
        }
    }

    /** The value blob stored for a CUSIP, if any. */
    public Optional<byte[]> get(String cusip) {
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer v = sec.get(txn, direct(ascii(cusip)));
            return v == null ? Optional.empty() : Optional.of(bytes(v));
        }
    }

    /** All CUSIPs for an issuer — a prefix scan of {@code sec} on the CUSIP-6 (or any leading fragment). */
    public List<String> byIssuer(String cusip6) {
        return prefixCusips(sec, ascii(cusip6), /*cusipIsWholeKey=*/true);
    }

    /** CUSIPs maturing in [{@code from}, {@code to}] inclusive — a range scan of {@code idx_maturity}. */
    public List<String> byMaturityRange(LocalDate from, LocalDate to) {
        return rangeCusips(byMaturity, dateKey(from), dateKey(to), 4);
    }

    /** CUSIPs whose coupon (scaled ×1e6) is in [{@code loScaled}, {@code hiScaled}] inclusive. */
    public List<String> byCouponRange(long loScaled, long hiScaled) {
        return rangeCusips(byCoupon, u64be(loScaled), u64be(hiScaled), 8);
    }

    /** CUSIPs in a geography — a prefix scan of {@code idx_geo} on a FIPS prefix (state `34`, county `34003`). */
    public List<String> byGeography(String fipsPrefix) {
        return prefixCusips(byGeo, ascii(fipsPrefix), /*cusipIsWholeKey=*/false);
    }

    // ---- scan helpers ----

    /** Prefix scan; {@code cusipIsWholeKey} true when the key IS the CUSIP (sec), false when CUSIP is the tail. */
    private List<String> prefixCusips(Dbi<ByteBuffer> dbi, byte[] prefix, boolean cusipIsWholeKey) {
        List<String> out = new ArrayList<>();
        try (Txn<ByteBuffer> txn = env.txnRead();
             CursorIterable<ByteBuffer> it = dbi.iterate(txn, KeyRange.atLeast(direct(prefix)))) {
            for (CursorIterable.KeyVal<ByteBuffer> kv : it) {
                byte[] k = bytes(kv.key());
                if (!startsWith(k, prefix)) {
                    break; // sorted order: once the prefix stops matching, we're done
                }
                out.add(cusip(k, cusipIsWholeKey ? 0 : k.length - 9));
            }
        }
        return out;
    }

    /** Range scan over a composite {@code fixed(headLen)||cusip(9)} key; returns the CUSIP tails in order.
     *  Seeks to the lower head with {@code atLeast} then stops at the inclusive upper bound by an explicit
     *  unsigned compare — robust across the variable-length bound keys (LMDB orders by unsigned bytes). */
    private List<String> rangeCusips(Dbi<ByteBuffer> dbi, byte[] loHead, byte[] hiHead, int headLen) {
        byte[] stop = concat(hiHead, CUSIP_MAX);     // >= any hiHead||cusip, so it's the inclusive upper bound
        List<String> out = new ArrayList<>();
        try (Txn<ByteBuffer> txn = env.txnRead();
             CursorIterable<ByteBuffer> it = dbi.iterate(txn, KeyRange.atLeast(direct(loHead)))) {
            for (CursorIterable.KeyVal<ByteBuffer> kv : it) {
                byte[] k = bytes(kv.key());
                if (Arrays.compareUnsigned(k, stop) > 0) {
                    break; // past the upper bound; sorted order means we're done
                }
                out.add(cusip(k, headLen));
            }
        }
        return out;
    }

    private static String cusip(byte[] key, int off) {
        return new String(key, off, key.length - off, StandardCharsets.US_ASCII);
    }
}
