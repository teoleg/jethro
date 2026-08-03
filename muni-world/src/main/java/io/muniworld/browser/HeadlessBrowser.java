package io.muniworld.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a JavaScript page to its final DOM by shelling out to headless Chromium ({@code --dump-dom}). EMMA
 * is a postback/JS site that serves no crawlable links in its raw HTML (ADR-0015 Phase 2 finding), so the
 * only way to reach its data automatically is to render it like a browser and read the resulting DOM. This
 * is the general capability; {@link io.muniworld.crawl.EmmaAutoFetcher} uses it against EMMA.
 *
 * <p>Needs a Chromium binary on the host ({@code muni.browser.bin}, default {@code chromium-browser} —
 * {@code sudo apt-get install chromium-browser} on the Pi). No cloud. Runs only when auto-fetch is enabled.
 */
@Component
public final class HeadlessBrowser {

    private static final Logger log = LoggerFactory.getLogger(HeadlessBrowser.class);

    private final String bin;
    private final String userAgent;
    private final int budgetMs;

    public HeadlessBrowser(
            @Value("${muni.browser.bin:chromium-browser}") String bin,
            @Value("${muni.http.user-agent:muni-world/0.1 (+municipal-data-collection)}") String userAgent,
            @Value("${muni.browser.render-budget-ms:8000}") int budgetMs) {
        this.bin = bin;
        this.userAgent = userAgent;
        this.budgetMs = budgetMs;
    }

    /** Render {@code url} and return the final DOM HTML (after JS runs). */
    public String render(String url) throws IOException {
        List<String> cmd = List.of(bin, "--headless", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
                "--dump-dom", "--virtual-time-budget=" + budgetMs, "--user-agent=" + userAgent, url);
        try {
            Process p = new ProcessBuilder(cmd).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(budgetMs / 1000 + 30L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("headless render timed out for " + url);
            }
            String html = new String(out);
            log.info("rendered {} ({} chars of DOM)", url, html.length());
            return html;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("render interrupted", e);
        }
    }

    /**
     * Load {@code url} and capture EVERY network request the page makes (via Chrome's net-log), so an
     * AJAX/XHR data endpoint built dynamically in JS is revealed even though it never appears in the HTML.
     * Returns the distinct request URLs. Longer budget so the grid's data request fires.
     */
    public List<String> networkRequests(String url) throws IOException {
        Path netlog = Files.createTempFile("muni-netlog", ".json");
        List<String> cmd = List.of(bin, "--headless", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
                "--log-net-log=" + netlog, "--net-log-capture-mode=Everything",
                "--virtual-time-budget=20000", "--dump-dom", "--user-agent=" + userAgent, url);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();   // drain stdout so the process can exit
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("network capture timed out for " + url);
            }
            String log = Files.readString(netlog);
            Set<String> urls = new LinkedHashSet<>();
            Matcher m = Pattern.compile("\"url\":\\s*\"([^\"]+)\"").matcher(log);
            while (m.find()) {
                urls.add(m.group(1).replace("\\/", "/"));
            }
            return new ArrayList<>(urls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("network capture interrupted", e);
        } finally {
            Files.deleteIfExists(netlog);
        }
    }
}
