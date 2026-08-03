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
    private final String recentUrl;
    private final Pattern osLinkPattern;
    private final boolean enabled;
    private final int latestCount;

    public EmmaAutoFetcher(
            HeadlessBrowser browser,
            PoliteFetcher fetcher,
            WatchlistCatalog watchlist,
            @Value("${muni.os.inbox.dir:os-inbox}") String inboxDir,
            @Value("${muni.emma.security-url-template:https://emma.msrb.org/Security/Details/{cusip}}") String urlTemplate,
            @Value("${muni.emma.recent-url:https://emma.msrb.org/MarketActivity/RecentOfficialstatements}") String recentUrl,
            @Value("${muni.emma.os-link-pattern:(?i)(\\.pdf(\\?|$)|officialstatement|/Document/|securitydocument|/P[0-9]|/ES[0-9])}")
            String osLinkPattern,
            @Value("${muni.emma.auto.enabled:false}") boolean enabled,
            @Value("${muni.emma.auto.latest-count:10}") int latestCount) {
        this.browser = browser;
        this.fetcher = fetcher;
        this.watchlist = watchlist;
        this.inbox = Path.of(inboxDir);
        this.urlTemplate = urlTemplate;
        this.recentUrl = recentUrl;
        this.osLinkPattern = Pattern.compile(osLinkPattern);
        this.enabled = enabled;
        this.latestCount = latestCount;
    }

    /** Debug: the links EMMA's recent-OS page renders to, so the OS-link pattern can be matched to reality. */
    public List<String> recentLinks() {
        try {
            return HtmlLinks.absoluteLinks(browser.render(recentUrl), recentUrl);
        } catch (Exception e) {
            log.warn("recentLinks render failed: {}", e.toString());
            return List.of();
        }
    }

    /** Debug: EMMA loads its grid via AJAX — surface the data endpoints / postback targets referenced in the
     *  rendered page so the real data URL (not the chrome links) can be found and hit directly. */
    public java.util.Map<String, Object> debugProbe() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            String dom = browser.render(recentUrl);
            out.put("htmlLength", dom.length());
            out.put("hasDataTable", dom.toLowerCase().contains("datatable"));
            out.put("hasAjaxLoader", dom.contains("ajax-loader"));
            java.util.Set<String> urls = new java.util.LinkedHashSet<>();
            var m = Pattern.compile(
                    "[\"']([^\"'\\s]*(?:Get|Data|Official|Activity|ajax|\\.axd|\\.ashx|/api/)[^\"'\\s]*)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(dom);
            while (m.find() && urls.size() < 80) {
                String u = m.group(1);
                if (!u.matches("(?i).*\\.(css|js|png|gif|jpg|ico|woff2?)(\\?.*)?$")) {
                    urls.add(u);
                }
            }
            out.put("dataUrls", new java.util.ArrayList<>(urls));
            java.util.Set<String> pb = new java.util.LinkedHashSet<>();
            var m2 = Pattern.compile("__doPostBack\\(&?#?39?;?['\"]?([^'\"&]+)").matcher(dom);
            while (m2.find() && pb.size() < 30) {
                pb.add(m2.group(1));
            }
            out.put("postbackTargets", new java.util.ArrayList<>(pb));
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

    /** Debug: capture EMMA's network requests and return the ones that look like DATA endpoints (not static
     *  assets / analytics) — the AJAX call the recent-OS grid makes for its rows. */
    public List<String> dataRequests() {
        try {
            List<String> all = browser.networkRequests(recentUrl);
            List<String> data = new ArrayList<>();
            for (String u : all) {
                String lo = u.toLowerCase();
                boolean asset = lo.matches(".*\\.(css|js|png|gif|jpg|jpeg|ico|woff2?|svg|map)(\\?.*)?$");
                boolean thirdParty = lo.contains("google") || lo.contains("fullstory") || lo.contains("gtm")
                        || lo.contains("fonts.") || lo.contains("analytics") || lo.contains("theice.com");
                if (u.startsWith("http") && !asset && !thirdParty) {
                    data.add(u);
                }
            }
            return data;
        } catch (Exception e) {
            log.warn("dataRequests failed: {}", e.toString());
            return List.of("error: " + e);
        }
    }

    /** Debug: after the (puppeteer) render, is the grid actually populated, and how does it link documents? */
    public java.util.Map<String, Object> debugGrid() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            String dom = browser.render(recentUrl);
            out.put("htmlLength", dom.length());
            out.put("trCount", countOf(dom, "<tr"));
            out.put("officialStatementMentions", countOf(dom.toLowerCase(), "official statement"));
            out.put("cusipTokens", regexSample(dom, "\\b[0-9]{3}[0-9A-Z]{5}[0-9]\\b", 15));
            out.put("doPostBack", regexSample(dom, "__doPostBack\\([^)]*\\)", 15));
            out.put("onclickDocLike", regexSample(dom,
                    "onclick=\"[^\"]*(?:Document|\\.pdf|/P[0-9]|/ES[0-9]|Official)[^\"]*\"", 15));
            out.put("hrefDocLike", regexSample(dom,
                    "href=\"[^\"]*(?:Document|\\.pdf|/P[0-9]|/ES[0-9]|Official)[^\"]*\"", 15));
            out.put("dataUrlAttrs", regexSample(dom, "data-[a-z-]+=\"[^\"]*(?:Document|\\.pdf|/[A-Z][0-9])[^\"]*\"", 15));
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

    private static int countOf(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    private static List<String> regexSample(String s, String regex, int max) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        var m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(s);
        while (m.find() && out.size() < max) {
            out.add(m.group());
        }
        return new ArrayList<>(out);
    }

    /** Load the LATEST official statements from EMMA's "Recent Official Statements" feed — no CUSIP needed. */
    public Result fetchLatest(int count) {
        try {
            String dom = browser.render(recentUrl);
            int rendered = HtmlLinks.absoluteLinks(dom, recentUrl).size();
            List<String> candidates = HtmlLinks.matching(dom, recentUrl, osLinkPattern);
            List<String> downloaded = downloadToInbox("latest", candidates, count);
            log.info("emma-latest: {} rendered links, {} OS candidates, {} PDFs downloaded",
                    rendered, candidates.size(), downloaded.size());
            return new Result("recent", recentUrl, rendered, candidates, downloaded, true, null);
        } catch (Exception e) {
            return new Result("recent", recentUrl, 0, List.of(), List.of(), false, e.getMessage());
        }
    }

    /** What one auto-fetch found: the rendered link count, the OS candidates, and what got downloaded. */
    public record Result(String cusip, String securityUrl, int renderedLinks,
                         List<String> candidates, List<String> downloaded, boolean ok, String error) {
    }

    /** Render a specific CUSIP's EMMA page, download its OS PDFs into the inbox. */
    public Result fetchToInbox(String cusip) {
        String url = urlTemplate.replace("{cusip}", cusip);
        try {
            String dom = browser.render(url);
            int rendered = HtmlLinks.absoluteLinks(dom, url).size();
            List<String> candidates = HtmlLinks.matching(dom, url, osLinkPattern);
            List<String> downloaded = downloadToInbox(cusip, candidates, Integer.MAX_VALUE);
            log.info("emma-auto {}: {} rendered links, {} OS candidates, {} PDFs downloaded",
                    cusip, rendered, candidates.size(), downloaded.size());
            return new Result(cusip, url, rendered, candidates, downloaded, true, null);
        } catch (Exception e) {
            return new Result(cusip, url, 0, List.of(), List.of(), false, e.getMessage());
        }
    }

    /** Download up to {@code max} PDF candidates into the inbox; a stable name per link avoids duplicates. */
    private List<String> downloadToInbox(String prefix, List<String> candidates, int max) throws Exception {
        Files.createDirectories(inbox);
        List<String> downloaded = new ArrayList<>();
        for (String link : candidates) {
            if (downloaded.size() >= max) {
                break;
            }
            RawArtifact pdf = fetcher.fetch("emma:" + prefix, link);
            if (isPdf(pdf)) {
                String tail = link.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
                if (tail.length() > 60) {
                    tail = tail.substring(tail.length() - 60);
                }
                Path dest = inbox.resolve(prefix + "-" + tail + ".pdf");
                Files.write(dest, pdf.body());
                downloaded.add(dest.getFileName().toString());
            }
        }
        return downloaded;
    }

    /** Scheduled hands-off pull: the LATEST official statements from EMMA (only when enabled + browser present). */
    @Scheduled(fixedDelayString = "${muni.emma.auto.scan-ms:3600000}")
    public void scheduledFetch() {
        if (!enabled) {
            return;
        }
        fetchLatest(latestCount);                     // pull the newest filings each cycle — no input needed
        for (WatchlistCatalog.Entry e : watchlist.entries()) {   // plus any specific CUSIPs you track
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
