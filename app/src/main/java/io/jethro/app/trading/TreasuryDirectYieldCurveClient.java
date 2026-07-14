package io.jethro.app.trading;

import io.jethro.trading.marketdata.sim.CurveMarkSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Daily Treasury Par Yield Curve from the OFFICIAL U.S. Treasury feed (ADR-0024 follow-up) —
 * free, keyless, and authoritative, so the live rates curve does not depend on any vendor's
 * paid tier (Finnhub's bond endpoints are premium-gated on free keys). Publishes once per
 * business day (~3:30pm ET); intraday the last business day's curve stands — honest and
 * stated, not simulated wiggle.
 *
 * <p>Feed: {@code home.treasury.gov/...pages/xml?data=daily_treasury_yield_curve&
 * field_tdr_date_value_month=YYYYMM} (per-month XML). The LAST entry is the most recent
 * business day; early in a month with no data yet, the previous month is fetched. Values are
 * PAR yields in percent — fed to the curve nodes as the same par≈zero approximation the rest
 * of the demo rates stack documents. Parsing is a pure static method, fixture-tested offline.
 */
public final class TreasuryDirectYieldCurveClient implements TreasuryCurveFetcher {

    private static final Logger log = LoggerFactory.getLogger(TreasuryDirectYieldCurveClient.class);
    private static final String URL_TEMPLATE = "https://home.treasury.gov/resource-center/"
            + "data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve"
            + "&field_tdr_date_value_month=%s";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** Feed tags for our nodes (1/2/5/10/30Y), aligned to {@link CurveMarkSource#TENORS}. */
    private static final String[] NODE_TAGS = {"BC_1YEAR", "BC_2YEAR", "BC_5YEAR", "BC_10YEAR", "BC_30YEAR"};

    private final HttpClient http;
    private final Duration timeout;

    public TreasuryDirectYieldCurveClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public String source() {
        return "treasury.gov (daily par yields)";
    }

    @Override
    public double[] fetchNodeZeros() {
        // Current month first; a fresh month can be empty (weekend/holiday start) — step back.
        LocalDate today = LocalDate.now();
        for (LocalDate month : new LocalDate[]{today, today.minusMonths(1)}) {
            double[] zeros = fetchMonth(MONTH.format(month));
            if (zeros != null) {
                return zeros;
            }
        }
        return null;
    }

    private double[] fetchMonth(String yyyymm) {
        String url = String.format(URL_TEMPLATE, yyyymm);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("treasury.gov yield curve {} → HTTP {}", yyyymm, resp.statusCode());
                return null;
            }
            return parseLatestNodeZeros(resp.body());
        } catch (Exception e) {
            log.warn("treasury.gov yield curve fetch failed ({}): {}", yyyymm, e.toString());
            return null;
        }
    }

    /**
     * Extracts the MOST RECENT day's node yields from the month XML, as fractions (4.85% →
     * 0.0485). Null if the month has no complete entry — the caller steps back a month or
     * falls through to the next curve source. Never throws (data-path rule).
     */
    static double[] parseLatestNodeZeros(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        // Entries appear in date order; take the LAST properties block that has all our nodes.
        Matcher block = Pattern.compile("<m:properties>(.*?)</m:properties>", Pattern.DOTALL).matcher(xml);
        double[] latest = null;
        while (block.find()) {
            double[] zeros = parseBlock(block.group(1));
            if (zeros != null) {
                latest = zeros; // keep overwriting — the last complete block is the newest day
            }
        }
        return latest;
    }

    private static double[] parseBlock(String block) {
        double[] zeros = new double[CurveMarkSource.TENORS.length];
        for (int i = 0; i < NODE_TAGS.length; i++) {
            Matcher m = Pattern.compile("<d:" + NODE_TAGS[i] + "[^>]*>([0-9.]+)</d:" + NODE_TAGS[i] + ">")
                    .matcher(block);
            if (!m.find()) {
                return null; // incomplete day (or a null-typed tag) — never guess a node
            }
            zeros[i] = Double.parseDouble(m.group(1)) / 100.0;
        }
        return zeros;
    }
}
