package io.muniworld.crawl;

import io.muniworld.bond.WatchlistCatalog;
import io.muniworld.browser.HeadlessBrowser;
import io.muniworld.ingest.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fully automatic EMMA fetch (ADR-0015 Phase 2 / ADR-0009): render a CUSIP's EMMA security page with a
 * headless browser (so the JS/postback-hidden document links appear), pull the Official Statement PDF links,
 * download them (politely — ADR-0008), and drop the PDFs into the OS inbox — where the folder loader
 * extracts them with no human in the loop. On a schedule it walks the watchlist and does this for every
 * tracked CUSIP.
 *
 * <p>OFF by default ({@code muni.emma.auto.enabled=true}) because it needs a Chromium binary on the host.
 * The {@link #fetchToInbox} result reports the rendered link count + candidates, so if EMMA's markup differs
 * the selectors are tuned from a real run rather than blind.
 */
@Service
public final class EmmaAutoFetcher {

    private static final Logger log = LoggerFactory.getLogger(EmmaAutoFetcher.class);

    private final HeadlessBrowser browser;
    private final PoliteFetcher fetcher;
    private final WatchlistCatalog watchlist;
    private final Path inbox;
    private final String urlTemplate;
    private final Pattern osLinkPattern;
    private final boolean enabled;

    public EmmaAutoFetcher(
            HeadlessBrowser browser,
            PoliteFetcher fetcher,
            WatchlistCatalog watchlist,
            @Value("${muni.os.inbox.dir:os-inbox}") String inboxDir,
            @Value("${muni.emma.security-url-template:https://emma.msrb.org/Security/Details/{cusip}}") String urlTemplate,
            @Value("${muni.emma.os-link-pattern:(?i)(\\.pdf(\\?|$)|officialstatement|/Document/|securitydocument|/P[0-9]|/ES[0-9])}")
            String osLinkPattern,
            @Value("${muni.emma.auto.enabled:false}") boolean enabled) {
        this.browser = browser;
        this.fetcher = fetcher;
        this.watchlist = watchlist;
        this.inbox = Path.of(inboxDir);
        this.urlTemplate = urlTemplate;
        this.osLinkPattern = Pattern.compile(osLinkPattern);
        this.enabled = enabled;
    }

    /** What one auto-fetch found: the rendered link count, the OS candidates, and what got downloaded. */
    public record Result(String cusip, String securityUrl, int renderedLinks,
                         List<String> candidates, List<String> downloaded, boolean ok, String error) {
    }

    /** Render the CUSIP's EMMA page, download its OS PDFs into the inbox. Manual + scheduled entry point. */
    public Result fetchToInbox(String cusip) {
        String url = urlTemplate.replace("{cusip}", cusip);
        try {
            String dom = browser.render(url);
            int rendered = HtmlLinks.absoluteLinks(dom, url).size();
            List<String> candidates = HtmlLinks.matching(dom, url, osLinkPattern);
            List<String> downloaded = new ArrayList<>();
            Files.createDirectories(inbox);
            int n = 0;
            for (String link : candidates) {
                RawArtifact pdf = fetcher.fetch("emma-auto:" + cusip, link);
                if (isPdf(pdf)) {
                    Path dest = inbox.resolve(cusip + "-" + (++n) + ".pdf");
                    Files.write(dest, pdf.body());
                    downloaded.add(dest.getFileName().toString());
                }
            }
            log.info("emma-auto {}: {} rendered links, {} OS candidates, {} PDFs downloaded",
                    cusip, rendered, candidates.size(), downloaded.size());
            return new Result(cusip, url, rendered, candidates, downloaded, true, null);
        } catch (Exception e) {
            return new Result(cusip, url, 0, List.of(), List.of(), false, e.getMessage());
        }
    }

    /** Scheduled: walk the watchlist and auto-fetch each CUSIP (only when enabled + a browser is present). */
    @Scheduled(fixedDelayString = "${muni.emma.auto.scan-ms:3600000}")
    public void scheduledFetch() {
        if (!enabled) {
            return;
        }
        for (WatchlistCatalog.Entry e : watchlist.entries()) {
            fetchToInbox(e.cusip());
        }
    }

    private static boolean isPdf(RawArtifact a) {
        byte[] b = a.body();
        if (b != null && b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return true;
        }
        return a.contentType() != null && a.contentType().toLowerCase().contains("pdf");
    }
}
