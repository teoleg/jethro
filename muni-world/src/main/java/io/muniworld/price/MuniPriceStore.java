package io.muniworld.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.muniworld.domain.PriceQuote;
import io.muniworld.store.MuniKeys;
import io.muniworld.store.MuniLmdbStore;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * The current-price store (ADR-0015) — a small LMDB tree (CUSIP → latest {@link PriceQuote} JSON) kept apart
 * from the OS terms index, so terms and market price stay distinct sources each with their own provenance.
 * The bonds table reads it to fill live economics; a bond with no quote here shows blank economics (never a
 * stale or invented price). Latest-wins per CUSIP (a newer trade print overwrites).
 */
@org.springframework.stereotype.Component
public final class MuniPriceStore {

    private final Env<ByteBuffer> env;
    private final Dbi<ByteBuffer> px;
    private final ObjectMapper mapper;

    public MuniPriceStore(MuniLmdbStore store, ObjectMapper mapper) {
        this.env = store.env();
        this.px = env.openDbi("px", DbiFlags.MDB_CREATE);
        this.mapper = mapper;
    }

    /** Store (overwrite) the latest quote for a CUSIP. */
    public void put(PriceQuote q) {
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            px.put(txn, MuniKeys.direct(MuniKeys.ascii(q.cusip())),
                    MuniKeys.direct(mapper.writeValueAsBytes(q)));
            txn.commit();
        } catch (Exception e) {
            throw new RuntimeException("failed to store price for " + q.cusip(), e);
        }
    }

    /** The latest quote for a CUSIP, if any. */
    public Optional<PriceQuote> get(String cusip) {
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer v = px.get(txn, MuniKeys.direct(MuniKeys.ascii(cusip)));
            if (v == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(MuniKeys.bytes(v), PriceQuote.class));
        } catch (Exception e) {
            throw new RuntimeException("failed to read price for " + cusip, e);
        }
    }
}
