package io.muniworld.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.muniworld.domain.Bond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetches a fund's latest <b>Form N-PORT</b> from SEC EDGAR and turns its municipal-debt holdings into
 * terms-only {@link Bond}s (ADR-0016). N-PORT is the fund's complete quarterly portfolio — a public SEC
 * record served to programs under the fair-access policy (declared User-Agent, well under the rate limits:
 * this makes two requests per fund per run).
 *
 * <p>Flow: {@code data.sec.gov/submissions/CIK##########.json} (registrant name + filing list) → the name
 * GATE (the registrant EDGAR reports must contain the registry's {@code expect_name}, or the fund is
 * refused — a mistyped CIK must fail loudly, not pollute silently) → the latest NPORT-P's
 * {@code primary_doc.xml} from the archives, landed as an immutable {@link RawArtifact} → parse.
 *
 * <p>Parsing keeps the ADR-0011 quarantine contract: only municipal debt with a 9-char CUSIP, name,
 * maturity and rate is ingested; cash/equities/repos and unkeyable rows are counted and skipped, never
 * force-fit. <b>No price is derived</b> — an N-PORT valuation is as-of the quarter, not a current mark.
 */
@Component
public final class EdgarNportConnector {

    private static final Logger log = LoggerFactory.getLogger(EdgarNportConnector.class);

    private final HttpFetcher http;
    private final ObjectMapper json;

    public EdgarNportConnector(HttpFetcher http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    /** One fund's ingest result — everything the status page needs to say what happened. */
    public record Result(String registrant, int filings, String newestFilingDate,
                         List<Bond> bonds, int skippedNonMuni, int quarantined) {
    }

    /**
     * Fetch + gate + parse the latest N-PORT cycle for {@code fund}. Throws with a named reason on refusal.
     *
     * <p><b>Cycle, not single filing:</b> a registrant TRUST holds several series (Vanguard's NY registrant
     * is the money-market fund AND the long-term fund), and each series files its own NPORT-P. Taking only
     * the newest filing captures one series and silently drops the rest — so every NPORT-P filed within
     * {@link #CYCLE_WINDOW_DAYS} of the newest is ingested, which is exactly one quarterly cycle across all
     * series. Upserts are idempotent, so overlap costs nothing.
     */
    public Result loadLatest(EdgarFundCatalog.Fund fund) throws Exception {
        String cik10 = String.format("%010d", Long.parseLong(fund.cik()));
        String sourceId = "edgar-nport:" + fund.cik();

        RawArtifact subs = http.fetch(sourceId, "https://data.sec.gov/submissions/CIK" + cik10 + ".json");
        JsonNode root = json.readTree(subs.body());
        String registrant = root.path("name").asText("");
        requireNameMatches(registrant, fund.expectName(), fund.cik());

        JsonNode recent = root.path("filings").path("recent");
        List<String> forms = texts(recent.path("form"));
        List<String> dates = texts(recent.path("filingDate"));
        List<Integer> cycle = latestCycleIndexes(forms, dates);
        if (cycle.isEmpty()) {
            throw new IllegalStateException("no NPORT-P filing listed for CIK " + fund.cik()
                    + " (" + registrant + ") — is this actually a registered fund?");
        }

        List<Bond> bonds = new ArrayList<>();
        int skipped = 0;
        int quarantined = 0;
        for (int idx : cycle) {
            String accession = recent.path("accessionNumber").get(idx).asText();
            String primaryDoc = recent.path("primaryDocument").get(idx).asText("primary_doc.xml");
            String url = "https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(fund.cik()) + "/"
                    + accession.replace("-", "") + "/" + primaryDoc;
            RawArtifact filing = http.fetch(sourceId, url);
            Parsed p = parseHoldings(filing.body());
            bonds.addAll(p.bonds());
            skipped += p.skippedNonMuni();
            quarantined += p.quarantined();
            log.info("N-PORT {} ({}, filed {}): {} muni bond(s), {} non-muni skipped, {} quarantined",
                    accession, registrant, dates.get(idx), p.bonds().size(), p.skippedNonMuni(),
                    p.quarantined());
            Thread.sleep(400);      // politeness — far under EDGAR's 10 req/s, deliberately
        }
        return new Result(registrant, cycle.size(), dates.get(cycle.get(0)), bonds, skipped, quarantined);
    }

    /** One quarterly cycle: series file within days of each other, quarters are ~91 days apart. */
    static final int CYCLE_WINDOW_DAYS = 45;

    /**
     * Indexes of every NPORT-P filed within {@link #CYCLE_WINDOW_DAYS} of the newest one — the latest
     * quarterly cycle across ALL of a registrant's series, without dragging in the previous quarter.
     * Package-private and pure so the windowing is TESTED.
     */
    static List<Integer> latestCycleIndexes(List<String> forms, List<String> dates) {
        LocalDate newest = null;
        for (int i = 0; i < forms.size(); i++) {
            if ("NPORT-P".equalsIgnoreCase(forms.get(i))) {
                LocalDate d = LocalDate.parse(dates.get(i));
                if (newest == null || d.isAfter(newest)) {
                    newest = d;
                }
            }
        }
        if (newest == null) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            if ("NPORT-P".equalsIgnoreCase(forms.get(i))
                    && !LocalDate.parse(dates.get(i)).isBefore(newest.minusDays(CYCLE_WINDOW_DAYS))) {
                out.add(i);
            }
        }
        return out;
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            out.add(n.asText());
        }
        return out;
    }

    /**
     * The gate that makes a hand-typed CIK safe: EDGAR's own registrant name must contain the expected
     * string, case-insensitively, or nothing is ingested.
     */
    static void requireNameMatches(String registrant, String expect, String cik) {
        if (expect == null || expect.isBlank()
                || !registrant.toLowerCase(Locale.ROOT).contains(expect.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("CIK " + cik + " is registered to '" + registrant
                    + "', which does not contain the expected '" + expect + "' — refusing to ingest "
                    + "(wrong CIK in seeds/edgar-funds.csv would pollute the security master)");
        }
    }

    record Parsed(List<Bond> bonds, int skippedNonMuni, int quarantined) {
    }

    /**
     * Pull the municipal-debt holdings out of an NPORT-P {@code primary_doc.xml}. Package-private and pure
     * so the shape contract is TESTED — this is the piece that must not rot when the SEC evolves the form.
     *
     * <p>Keeps: {@code issuerCat=MUN}, or plain {@code assetCat=DBT} rows when the issuer category is
     * absent (older filings). Requires cusip/name/maturity/rate — the same durable-terms rule as the
     * normaliser; anything else increments a counter and is dropped in the open.
     */
    static Parsed parseHoldings(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // XXE hardening — this is third-party XML off the network.
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        NodeList secs = doc.getElementsByTagNameNS("*", "invstOrSec");
        List<Bond> bonds = new ArrayList<>();
        int skippedNonMuni = 0;
        int quarantined = 0;
        for (int i = 0; i < secs.getLength(); i++) {
            Element sec = (Element) secs.item(i);
            String issuerCat = text(sec, "issuerCat");
            String assetCat = text(sec, "assetCat");
            boolean muni = "MUN".equalsIgnoreCase(issuerCat)
                    || (issuerCat.isBlank() && "DBT".equalsIgnoreCase(assetCat));
            if (!muni) {
                skippedNonMuni++;
                continue;
            }
            String cusip = text(sec, "cusip").toUpperCase(Locale.ROOT);
            String name = text(sec, "name");
            String maturity = text(sec, "maturityDt");
            String rate = text(sec, "annualizedRt");
            if (!cusip.matches("[0-9A-Z]{9}") || name.isBlank() || maturity.isBlank() || rate.isBlank()) {
                quarantined++;   // muni debt but not keyable/complete — counted, never force-fit
                continue;
            }
            try {
                bonds.add(new Bond(cusip, name, new BigDecimal(rate).stripTrailingZeros(),
                        LocalDate.parse(maturity), null, null, null, null, null, null, null));
            } catch (RuntimeException e) {
                quarantined++;   // unparseable date/rate — same treatment
            }
        }
        return new Parsed(bonds, skippedNonMuni, quarantined);
    }

    /** First matching descendant's text, namespace-agnostic; empty string when absent. */
    private static String text(Element parent, String localName) {
        NodeList n = parent.getElementsByTagNameNS("*", localName);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().strip();
    }
}
