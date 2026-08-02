package io.muniworld.crawl;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny, dependency-free link extractor: pull {@code href}/{@code src} URLs out of HTML and resolve them
 * against the page's base URL, so the discovery step (ADR-0015 Phase 2) can find the Official Statement /
 * document links on a fetched EMMA page without a full HTML parser. Deterministic and testable.
 */
public final class HtmlLinks {

    private static final Pattern HREF = Pattern.compile(
            "(?:href|src)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private HtmlLinks() {
    }

    /** All links in the page, absolute, de-duplicated, in document order. */
    public static List<String> absoluteLinks(String html, String baseUrl) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null) {
            return List.of();
        }
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String abs = resolve(baseUrl, m.group(1).trim());
            if (abs != null) {
                out.add(abs);
            }
        }
        return new ArrayList<>(out);
    }

    /** Links whose absolute form matches {@code filter} (e.g. an OS / {@code .pdf} pattern). */
    public static List<String> matching(String html, String baseUrl, Pattern filter) {
        List<String> out = new ArrayList<>();
        for (String link : absoluteLinks(html, baseUrl)) {
            if (filter.matcher(link).find()) {
                out.add(link);
            }
        }
        return out;
    }

    private static String resolve(String base, String href) {
        if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:") || href.startsWith("mailto:")) {
            return null;
        }
        try {
            return URI.create(base).resolve(href).toString();
        } catch (RuntimeException e) {
            return null;   // malformed href — skip, don't fail the whole page
        }
    }
}
