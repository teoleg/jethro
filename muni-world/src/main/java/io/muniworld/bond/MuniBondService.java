package io.muniworld.bond;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.muniworld.domain.Bond;
import io.muniworld.domain.BondMath;
import io.muniworld.domain.BondRow;
import io.muniworld.store.MuniKeys;
import io.muniworld.store.MuniSearchIndex;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores bonds in the LMDB B-tree index (ADR-0013) as JSON blobs keyed by CUSIP, and reads them back as
 * {@link BondRow}s with the economic indicators computed on the fly ({@link BondMath}). The index's
 * ordered keys give the by-issuer (CUSIP-6 prefix) and by-geography (FIPS prefix) scans the table uses.
 */
@Service
public final class MuniBondService {

    private final MuniSearchIndex index;
    private final ObjectMapper mapper;

    public MuniBondService(MuniSearchIndex index, ObjectMapper mapper) {
        this.index = index;
        this.mapper = mapper;
    }

    /** Store a bond: JSON value under its CUSIP, plus the maturity/coupon/geo secondary indexes. */
    public void index(Bond b) {
        try {
            long couponScaled = MuniKeys.couponScaled(b.coupon());
            index.indexSecurity(b.cusip(), b.maturity(), couponScaled, b.geoFips(),
                    mapper.writeValueAsBytes(b));
        } catch (Exception e) {
            throw new RuntimeException("failed to index bond " + b.cusip(), e);
        }
    }

    public Optional<BondRow> get(String cusip) {
        return index.get(cusip).map(this::toBond).map(this::toRow);
    }

    /** All bonds for an issuer (CUSIP-6 prefix), as rows with indicators. */
    public List<BondRow> byIssuer(String cusip6) {
        return rows(index.byIssuer(cusip6));
    }

    /** All bonds in a geography (FIPS prefix), as rows with indicators. */
    public List<BondRow> byGeography(String fipsPrefix) {
        return rows(index.byGeography(fipsPrefix));
    }

    private List<BondRow> rows(List<String> cusips) {
        List<BondRow> out = new ArrayList<>(cusips.size());
        for (String c : cusips) {
            index.get(c).ifPresent(bytes -> out.add(toRow(toBond(bytes))));
        }
        return out;
    }

    private Bond toBond(byte[] json) {
        try {
            return mapper.readValue(json, Bond.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to read bond json", e);
        }
    }

    /** Compute the display row (indicators as of today's settlement). */
    public BondRow toRow(Bond b) {
        double coupon = b.coupon().doubleValue();
        double price = b.price().doubleValue();
        LocalDate settle = LocalDate.now();
        double callPrice = b.callPrice() == null ? 0.0 : b.callPrice().doubleValue();
        String call = b.callDate() == null ? "—"
                : b.callDate() + " @" + (b.callPrice() == null ? "100" : b.callPrice().toPlainString());
        return new BondRow(
                b.cusip(), b.issuer(), coupon, b.maturity().toString(), price,
                r(BondMath.currentYield(coupon, price), 3),
                r(BondMath.ytm(coupon, settle, b.maturity(), price), 3),
                r(BondMath.ytw(coupon, settle, b.maturity(), price, b.callDate(), callPrice), 3),
                r(BondMath.modDuration(coupon, settle, b.maturity(), price), 2),
                r(BondMath.convexity(coupon, settle, b.maturity(), price), 2),
                r(BondMath.accrued(coupon, settle, b.maturity()), 3),
                b.taxStatus(), call, b.rating());
    }

    private static double r(double v, int dp) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }
}
