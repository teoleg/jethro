package io.jethro.app.social;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the instruments a post refers to (ADR-0050): {@code $CASHTAGS} and bare whole-word id
 * mentions, kept only when they resolve to the live universe. This is the deterministic "which
 * securities" step — code, not the model (the SLM's job in ADR-0045 is the sector, never the names).
 */
public final class Cashtags {

    private static final Pattern CASHTAG = Pattern.compile("\\$([A-Za-z]{1,6})");

    private Cashtags() {
    }

    public static Set<String> extract(String text, Set<String> universe) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = CASHTAG.matcher(text);
        while (m.find()) {
            String sym = m.group(1).toUpperCase();
            if (universe.contains(sym)) {
                out.add(sym);
            }
        }
        // Bare mentions of an id as a whole word (e.g. "AAPL" without the $).
        for (String id : universe) {
            if (!out.contains(id) && Pattern.compile("\\b" + Pattern.quote(id) + "\\b").matcher(text).find()) {
                out.add(id);
            }
        }
        return out;
    }
}
