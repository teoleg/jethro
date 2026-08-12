package io.muniworld.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UI is server-served static HTML with no build step (ADR-0028), so nothing type-checks it: a handler
 * referenced by a button or a template that was never DEFINED fails only in the browser, at the moment a
 * user clicks it.
 *
 * <p>That shipped: a patch inserted a {@code callText(r)} call site into the bond detail while its
 * function definition landed in the other page, and every bond detail rendered "detail unavailable" —
 * the ReferenceError was swallowed by the panel's own catch block. This test walks each page's script and
 * asserts that every function it calls from an {@code onclick} or a template expression actually exists
 * in that same page.
 */
class StaticPageTest {

    /** Callables that are the browser's or standard JS, not ours. */
    private static final Set<String> BUILTINS = Set.of(
            "encodeURIComponent", "decodeURIComponent", "Number", "String", "Boolean", "Math", "JSON",
            "isNaN", "parseInt", "parseFloat", "fetch", "alert", "confirm", "setTimeout", "setInterval",
            "Array", "Object", "Date", "if", "for", "while", "switch", "catch", "return", "function",
            "typeof");

    private static final Pattern DEFINED = Pattern.compile(
            "(?:function\\s+([A-Za-z_$][\\w$]*)\\s*\\()|(?:(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=)");
    private static final Pattern CALLED_IN_ONCLICK = Pattern.compile("on\\w+=\"([A-Za-z_$][\\w$]*)\\(");
    private static final Pattern CALLED_IN_TEMPLATE = Pattern.compile("\\$\\{\\s*([A-Za-z_$][\\w$]*)\\(");

    @Test
    void everyHandlerAndTemplateCallIsDefinedOnItsOwnPage() throws Exception {
        for (String page : List.of("/static/index.html", "/static/tv.html", "/static/model.html")) {
            String html = read(page);

            Set<String> defined = new LinkedHashSet<>();
            Matcher d = DEFINED.matcher(html);
            while (d.find()) {
                defined.add(d.group(1) != null ? d.group(1) : d.group(2));
            }

            Set<String> called = new LinkedHashSet<>();
            for (Pattern p : List.of(CALLED_IN_ONCLICK, CALLED_IN_TEMPLATE)) {
                Matcher m = p.matcher(html);
                while (m.find()) {
                    called.add(m.group(1));
                }
            }

            assertFalse(called.isEmpty(), page + ": found no call sites at all — the scan is broken");
            for (String fn : called) {
                assertTrue(BUILTINS.contains(fn) || defined.contains(fn),
                        page + " calls " + fn + "() but never defines it — it would fail in the browser, "
                        + "silently, inside a catch block. Defined here: " + defined);
            }
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = StaticPageTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing static page: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
