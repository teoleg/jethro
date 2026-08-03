package io.muniworld.ingest;

import java.util.List;

/**
 * EMMA (MSRB) security-landing connector (ADR-0004). EMMA is the authoritative disclosure hub — Official
 * Statements, continuing disclosures, trade history — but it exposes no open, row-shaped terms API: a
 * security's page is an HTML/document surface keyed by CUSIP-9. So this connector's job is <b>landing</b>,
 * not parsing: it fetches the security page for a CUSIP as an immutable {@link RawArtifact} (ADR-0005
 * provenance), and the structured terms (coupon, maturity, call schedule, tax status) are lifted from the
 * landed Official Statement by the ADR-0010 document-extraction stage, then normalised like any other rows.
 *
 * <p>This is deliberately honest about the gap to numbers: EMMA gives us the <em>document</em>; it does not
 * hand us a bond's terms as JSON. Fabricating terms off a page shape would violate the "no invented numbers"
 * discipline, so we land the artifact and let extraction do the reading under review.
 *
 * <p>URL template default: {@code https://emma.msrb.org/Security/Details/<cusip9>}. The live fetch runs on a
 * networked host (the Pi); the sandbox blocks emma.msrb.org, which surfaces as a failed landing, not a guess.
 */
public final class EmmaConnector implements SourceConnector {

    /** {@code {cusip}} is substituted with the CUSIP-9. */
    public static final String DEFAULT_URL_TEMPLATE = "https://emma.msrb.org/Security/Details/{cusip}";

    private final String cusip9;
    private final String urlTemplate;
    private final HttpFetcher http;

    public EmmaConnector(String cusip9, HttpFetcher http) {
        this(cusip9, DEFAULT_URL_TEMPLATE, http);
    }

    public EmmaConnector(String cusip9, String urlTemplate, HttpFetcher http) {
        this.cusip9 = cusip9;
        this.urlTemplate = urlTemplate;
        this.http = http;
    }

    @Override
    public String sourceId() {
        return "emma:" + cusip9;
    }

    public String url() {
        return urlTemplate.replace("{cusip}", cusip9);
    }

    @Override
    public List<RawArtifact> fetch() {
        return List.of(http.fetch(sourceId(), url()));
    }
}
