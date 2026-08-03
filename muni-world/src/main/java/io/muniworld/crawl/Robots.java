package io.muniworld.crawl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A minimal, correct-enough robots.txt evaluator (ADR-0008 polite crawling): parse the rules for our
 * user-agent (falling back to the {@code *} group) and answer {@link #allowed(String)} by the standard
 * longest-match rule — the most specific matching path wins, and {@code Allow} beats {@code Disallow} on a
 * tie. Supports {@code *} wildcards and a trailing {@code $} anchor. Fail-open only on a genuinely empty
 * ruleset; an explicit {@code Disallow} is always honoured.
 */
public final class Robots {

    private record Rule(boolean allow, String path, Pattern regex) {
    }

    private final List<Rule> rules;

    private Robots(List<Rule> rules) {
        this.rules = rules;
    }

    /** Parse robots.txt content, selecting the group for {@code userAgent} (else the {@code *} group). */
    public static Robots parse(String content, String userAgent) {
        String uaToken = productToken(userAgent);
        List<String> starRules = new ArrayList<>();
        List<String> uaRules = new ArrayList<>();
        List<String> current = null;
        boolean groupHasRules = false;

        for (String raw : content.split("\r?\n")) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            if (field.equals("user-agent")) {
                if (groupHasRules) {
                    current = null;              // a new UA line after rules starts a fresh group
                    groupHasRules = false;
                }
                String ua = value.toLowerCase(Locale.ROOT);
                if (ua.equals("*")) {
                    current = starRules;
                } else if (!uaToken.isEmpty() && uaToken.contains(ua)) {
                    current = uaRules;
                }
            } else if ((field.equals("disallow") || field.equals("allow")) && current != null) {
                current.add(field + " " + value);
                groupHasRules = true;
            }
        }
        List<String> chosen = !uaRules.isEmpty() ? uaRules : starRules;
        return new Robots(compile(chosen));
    }

    /** Is {@code path} (e.g. {@code /Security/Details/...}) crawlable under the selected group's rules? */
    public boolean allowed(String path) {
        Rule best = null;
        for (Rule r : rules) {
            if (r.regex.matcher(path).find()) {
                // longest pattern wins; on equal length, Allow wins over Disallow
                if (best == null || r.path.length() > best.path.length()
                        || (r.path.length() == best.path.length() && r.allow && !best.allow)) {
                    best = r;
                }
            }
        }
        return best == null || best.allow;
    }

    // ---- parsing helpers ----

    private static List<Rule> compile(List<String> raw) {
        List<Rule> out = new ArrayList<>();
        for (String s : raw) {
            boolean allow = s.startsWith("allow ");
            String path = s.substring(s.indexOf(' ') + 1).trim();
            if (path.isEmpty()) {
                continue;   // "Disallow:" with no path = allow everything → no rule
            }
            out.add(new Rule(allow, path, toRegex(path)));
        }
        return out;
    }

    /** robots path → anchored-at-start regex; {@code *} → {@code .*}, trailing {@code $} anchors the end. */
    private static Pattern toRegex(String path) {
        boolean anchorEnd = path.endsWith("$");
        String p = anchorEnd ? path.substring(0, path.length() - 1) : path;
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (anchorEnd) {
            sb.append('$');
        }
        return Pattern.compile(sb.toString());
    }

    private static String stripComment(String line) {
        int h = line.indexOf('#');
        return h < 0 ? line : line.substring(0, h);
    }

    /** The product token of a UA string, lower-cased, e.g. "muni-world/0.1 (...)" → "muni-world/0.1". */
    private static String productToken(String ua) {
        if (ua == null) {
            return "";
        }
        String s = ua.trim().toLowerCase(Locale.ROOT);
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }
}
