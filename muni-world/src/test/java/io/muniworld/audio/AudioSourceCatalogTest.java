package io.muniworld.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The feed registry (ADR-0014) is config that SHIPS with the jar: parsed from the classpath, never seeded,
 * merged or rewritten on a host. A feed is <b>capturable</b> only when enabled AND given a source.
 */
class AudioSourceCatalogTest {

    @Test
    void theShippedRegistryStreamsWithNothingToConfigure() {
        // The owner should never have to paste a URL or bind anything: a fresh install must already have a
        // live feed. This is the contract that broke twice (a registry of blank rows, then a shipped stream
        // that never reached an existing install), so it is asserted on the real shipped file.
        AudioSourceCatalog cat = new AudioSourceCatalog();

        assertFalse(cat.all().isEmpty(), "the shipped registry lists at least one feed");
        assertFalse(cat.capturable().isEmpty(), "and at least one is live out of the box");

        AudioSource bbg = cat.all().stream().filter(s -> s.id().equals("tv-bloomberg")).findFirst()
                .orElseThrow(() -> new AssertionError("the Bloomberg feed must ship configured"));
        assertTrue(bbg.enabled(), "shipped enabled");
        assertTrue(bbg.device().startsWith("yt:") || bbg.device().startsWith("url:"),
                "the source is an ONLINE stream, not a host device: " + bbg.device());
        assertTrue(bbg.capturable());
    }

    @Test
    void parsesFeedsSkipsCommentsAndComputesCapturable() {
        var feeds = AudioSourceCatalog.parse("""
                id|label|publisher|category|device|chunk_seconds|enabled|notes
                # a comment line is ignored
                tv-a|A|A|tv|yt:https://example.com/live|120|true|configured + enabled
                tv-b|B|B|tv||300|true|enabled but no source
                tv-c|C|C|tv|url:https://example.com/s.m3u8|60|false|configured but disabled
                """);

        assertEquals(3, feeds.size(), "header, comment and blank lines are skipped");
        assertTrue(feeds.get(0).capturable(), "enabled + a source → captured");
        assertEquals(120, feeds.get(0).chunkSeconds());
        assertFalse(feeds.get(1).capturable(), "enabled but no source → not captured, still listed");
        assertFalse(feeds.get(2).capturable(), "has a source but disabled → not captured, still listed");
    }
}
