package io.muniworld.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TV/audio source registry (ADR-0014) parses the editable feed catalog, skips comments/header, and marks
 * a feed <b>capturable</b> only when it is enabled AND device-bound — so a listed-but-unbound feed shows in
 * the UI without being recorded.
 */
class AudioSourceCatalogTest {

    @Test
    void parsesFeedsAndComputesCapturable() throws IOException {
        Path csv = Files.createTempFile("audio-sources", ".csv");
        Files.writeString(csv, """
                id|label|publisher|category|device|chunk_seconds|enabled|notes
                # a comment line is ignored
                tv-bloomberg|Bloomberg TV|Bloomberg|tv|pulse:default.monitor|300|true|bound + enabled
                tv-cnbc|CNBC|CNBC|tv||300|true|enabled but no device
                tv-yahoo|Yahoo Finance|Yahoo|tv|alsa:hw:1,0|120|false|device with a comma, but disabled
                """);

        AudioSourceCatalog cat = new AudioSourceCatalog(csv.toString());

        // The catalog also merges any SHIPPED feed the file lacks, so assert on the rows under test by id
        // rather than by position/count — a new shipped feed must not break this.
        AudioSource bbg = byId(cat, "tv-bloomberg");
        AudioSource cnbc = byId(cat, "tv-cnbc");
        AudioSource yahoo = byId(cat, "tv-yahoo");

        assertTrue(cat.capturable().stream().anyMatch(f -> f.id().equals("tv-bloomberg")),
                "enabled + device-bound → captured");
        assertEquals("pulse:default.monitor", bbg.device(), "an EXISTING binding is never overwritten");
        assertEquals(300, bbg.chunkSeconds());

        assertFalse(cnbc.capturable(), "enabled but unbound → not captured, still listed");
        assertFalse(yahoo.capturable(), "bound but disabled → not captured, still listed");
        assertEquals("alsa:hw:1,0", yahoo.device(), "pipe-delimited: comma in the device survives");
    }

    @Test
    void missingFileFallsBackToClasspathDefault() {
        // blank path → the committed classpath catalog, read-only (nothing is seeded or merged onto disk)
        AudioSourceCatalog cat = new AudioSourceCatalog("");
        assertTrue(cat.all().size() >= 2, "the default registry lists the shipped feeds");
        assertEquals("", cat.file(), "no host file — the in-jar catalog is never written to");
    }

    @Test
    void shippedFeedsFillAnUnconfiguredRowButNeverOverwriteABinding() throws IOException {
        // The upgrade path that was missing: an EXISTING registry of blank rows never saw a newly shipped
        // source, so a working stream shipped and the operator still faced an empty list.
        Path csv = Files.createTempFile("audio-sources", ".csv");
        Files.writeString(csv, """
                id|label|publisher|category|device|chunk_seconds|enabled|notes
                tv-bloomberg|Bloomberg TV|Bloomberg|tv||300|false|unconfigured
                tv-mine|My Feed|Me|tv|pulse:mine.monitor|60|true|my own binding
                """);

        AudioSourceCatalog cat = new AudioSourceCatalog(csv.toString());

        assertTrue(byId(cat, "tv-bloomberg").device().startsWith("url:"),
                "an unconfigured row is filled from the shipped template");
        assertEquals("pulse:mine.monitor", byId(cat, "tv-mine").device(),
                "a row the operator configured is left exactly as it is");
    }

    private static AudioSource byId(AudioSourceCatalog cat, String id) {
        return cat.all().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }
}
