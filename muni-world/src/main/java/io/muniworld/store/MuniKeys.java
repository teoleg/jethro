package io.muniworld.store;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Key/value byte encoding for the LMDB B-tree search index (ADR-0013). LMDB compares keys as UNSIGNED byte
 * strings, so every numeric/date key must be <b>fixed-width big-endian</b> for lexical order to equal
 * numeric/chronological order, and identifiers must be fixed-width so prefixes align (CUSIP-6 ⊂ CUSIP-9,
 * state ⊂ county FIPS). Getting this wrong silently breaks ordering — hence one place, unit-tested.
 */
public final class MuniKeys {

    private MuniKeys() {
    }

    /** ASCII bytes for a fixed-width identifier (CUSIP, FIPS). */
    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Unsigned 32-bit big-endian. */
    public static byte[] u32be(long v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** Unsigned 64-bit big-endian. */
    public static byte[] u64be(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        return b;
    }

    /** A date as days-since-epoch, big-endian — sorts chronologically. Assumes dates &ge; 1970 (maturities). */
    public static byte[] dateKey(LocalDate d) {
        return u32be(d.toEpochDay());
    }

    /** Coupon as a scaled long (×1e6) — exact decimal, never binary FP. Positive coupons sort numerically. */
    public static long couponScaled(BigDecimal coupon) {
        return coupon.movePointRight(6).longValueExact();
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    /** A direct ByteBuffer (LMDB requires direct buffers), positioned for reading. */
    public static ByteBuffer direct(byte[] b) {
        ByteBuffer bb = ByteBuffer.allocateDirect(b.length);
        bb.put(b).flip();
        return bb;
    }

    /** Copy a ByteBuffer's remaining bytes without disturbing its position. */
    public static byte[] bytes(ByteBuffer bb) {
        byte[] b = new byte[bb.remaining()];
        bb.duplicate().get(b);
        return b;
    }

    /** True if {@code key} begins with {@code prefix} (for terminating a prefix scan). */
    public static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
