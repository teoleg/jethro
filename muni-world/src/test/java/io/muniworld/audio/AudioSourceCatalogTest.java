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

        List<AudioSource> all = cat.all();
        assertEquals(3, all.size(), "header + comment skipped, three feeds parsed");

        List<AudioSource> capturable = cat.capturable();
        assertEquals(1, capturable.size(), "only the enabled + device-bound feed is captured");
        assertEquals("tv-bloomberg", capturable.get(0).id());
        assertEquals(300, capturable.get(0).chunkSeconds());

        assertFalse(cat.all().get(1).capturable(), "enabled but unbound → not captured, still listed");
        assertFalse(cat.all().get(2).capturable(), "bound but disabled → not captured, still listed");
        assertEquals("alsa:hw:1,0", cat.all().get(2).device(), "pipe-delimited: comma in the device survives");
    }

    @Test
    void missingFileFallsBackToClasspathDefault() {
        // blank path → the committed classpath catalog (feeds listed, none device-bound by default)
        AudioSourceCatalog cat = new AudioSourceCatalog("");
        assertTrue(cat.all().size() >= 2, "the default registry lists the starter feeds");
        assertTrue(cat.capturable().isEmpty(), "nothing is capturable until a device is bound on the host");
    }
}
