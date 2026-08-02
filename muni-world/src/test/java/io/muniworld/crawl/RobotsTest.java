package io.muniworld.crawl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The robots.txt evaluator (ADR-0008): longest-match wins, Allow beats Disallow on a tie, a UA-specific group
 * overrides the {@code *} group, and no/empty robots means allow (but the crawl still rate-limits).
 */
class RobotsTest {

    private static final String ROBOTS = """
            User-agent: *
            Disallow: /private
            Allow: /private/public

            User-agent: badbot
            Disallow: /
            """;

    @Test
    void starGroupLongestMatchWithAllowOverride() {
        Robots r = Robots.parse(ROBOTS, "muni-world/0.1 (+x)");
        assertTrue(r.allowed("/Security/Details/649122AB1"), "unlisted path → allowed");
        assertFalse(r.allowed("/private/secret"), "under Disallow /private");
        assertTrue(r.allowed("/private/public/os.pdf"), "longer Allow /private/public wins the tie");
    }

    @Test
    void uaSpecificGroupOverridesStar() {
        Robots r = Robots.parse(ROBOTS, "badbot/1.0");
        assertFalse(r.allowed("/anything"), "badbot is disallowed everywhere by its own group");
    }

    @Test
    void noRobotsAllowsEverything() {
        assertTrue(Robots.parse("", "muni-world/0.1").allowed("/whatever"));
    }
}
