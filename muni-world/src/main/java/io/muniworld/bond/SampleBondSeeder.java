package io.muniworld.bond;

import io.muniworld.domain.Bond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Loads the packaged ILLUSTRATIVE sample bonds into the LMDB index at startup so the bonds table has real,
 * computed economics to show before any live connector runs. Sample data is clearly flagged
 * ({@code sample=true}); a networked host disables it ({@code muni.seed.sample-bonds=false}) and loads real
 * bonds via the EMMA/Open-Data connectors instead.
 */
@Component
@ConditionalOnProperty(name = "muni.seed.sample-bonds", havingValue = "true", matchIfMissing = true)
public final class SampleBondSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleBondSeeder.class);

    private final MuniBondService bonds;

    public SampleBondSeeder(MuniBondService bonds) {
        this.bonds = bonds;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = 0;
        try (InputStream in = getClass().getResourceAsStream("/seeds/sample-bonds.csv")) {
            if (in == null) {
                return;
            }
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
            // header: cusip,issuer,coupon,maturity,dated,price,tax,callDate,callPrice,rating,geoFips
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(",", -1); // keep empty trailing fields
                if (f.length < 11) {
                    continue;
                }
                bonds.index(new Bond(
                        f[0], f[1], new BigDecimal(f[2]),
                        LocalDate.parse(f[3]), date(f[4]),
                        new BigDecimal(f[5]), f[6],
                        date(f[7]), f[8].isBlank() ? null : new BigDecimal(f[8]),
                        f[9], f[10], true));
                n++;
            }
            log.info("seeded {} illustrative sample bonds into the LMDB index", n);
        } catch (Exception e) {
            log.warn("sample-bond seed failed: {}", e.toString());
        }
    }

    private static LocalDate date(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s);
    }
}
