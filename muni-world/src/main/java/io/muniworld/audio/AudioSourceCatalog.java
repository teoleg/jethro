package io.muniworld.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The TV/audio feed registry (ADR-0014): the shipped {@code /seeds/audio-sources.csv}, read once at startup.
 *
 * <p><b>Read-only, one copy, no host file.</b> This used to seed a writable copy on the host, merge shipped
 * rows into it, and be rewritten by a "bind" button. Every one of those moving parts caused a failure the
 * owner had to debug: a tracked file that aborted {@code git pull} twice, a live copy that silently ignored a
 * newly shipped stream, and two template copies where editing the wrong one changed nothing. The registry is
 * config that ships with the jar — to change a feed, edit this CSV and rebuild.
 *
 * <p><b>Pipe-delimited</b> ({@code id|label|publisher|category|device|chunk_seconds|enabled|notes}); lines
 * starting with {@code #} are comments. The capture loop drives from {@link #capturable()}.
 */
@Component
public final class AudioSourceCatalog {

    private static final Logger log = LoggerFactory.getLogger(AudioSourceCatalog.class);

    private final List<AudioSource> sources;

    public AudioSourceCatalog() {
        this.sources = load();
        log.info("AudioSourceCatalog: {} feed(s), {} capturable", sources.size(), capturable().size());
    }

    /** All registered feeds (capturable or not) — for the API/UI. */
    public List<AudioSource> all() {
        return sources;
    }

    /** The feeds the capture loop should actually record (enabled AND with a source). */
    public List<AudioSource> capturable() {
        return sources.stream().filter(AudioSource::capturable).toList();
    }

    private static List<AudioSource> load() {
        try (InputStream in = AudioSourceCatalog.class.getResourceAsStream("/seeds/audio-sources.csv")) {
            return in == null ? List.of() : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("could not read the shipped audio source registry: {}", e.toString());
            return List.of();
        }
    }

    static List<AudioSource> parse(String csv) {
        List<AudioSource> out = new ArrayList<>();
        for (String raw : csv.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("id|")) {
                continue;   // skip header + comments + blanks
            }
            String[] f = line.split("\\|", 8);
            if (f.length < 7) {
                continue;
            }
            out.add(new AudioSource(f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip(),
                    f[4].strip(), parseInt(f[5].strip(), 300), "true".equalsIgnoreCase(f[6].strip())));
        }
        return out;
    }

    private static int parseInt(String s, int dflt) {
        try {
            return s.isEmpty() ? dflt : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
