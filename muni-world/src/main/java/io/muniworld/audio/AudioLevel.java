package io.muniworld.audio;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measures how loud a captured clip actually is, straight off the PCM samples.
 *
 * <p>This exists because byte count proves nothing. A PulseAudio {@code .monitor} of a sink with no active
 * stream delivers a full-length, perfectly-formed run of ZEROS — so a capture can look completely healthy
 * (right duration, right size, ffmpeg exit 0, whisper exit 0) and contain no sound at all. Whisper then says
 * {@code [BLANK_AUDIO]}, which reads as "the ASR failed" when it actually means "there was nothing to hear".
 *
 * <p>Peak and RMS in dBFS separate those two cases as a FACT rather than an inference:
 * <ul>
 *   <li><b>peak = -inf (silent)</b> — the device delivered digital silence: nothing is playing into that
 *       sink, or the audio is leaving through a different one.</li>
 *   <li><b>audible level but no words</b> — the device is right and something was playing; the ASR simply
 *       found no speech (music, a jingle, crowd noise, or a model too small for it).</li>
 * </ul>
 *
 * Pure arithmetic over the samples — no model, nothing inferred (invariant 7).
 */
public final class AudioLevel {

    /** Below this peak dBFS a clip is treated as silence — 16-bit noise floor territory. */
    private static final double SILENCE_DBFS = -60.0;

    private AudioLevel() {
    }

    /** Peak/RMS of 16-bit PCM WAV bytes. Returns {@code silent:true} when there is effectively no signal. */
    public static Map<String, Object> of(byte[] wav) {
        Map<String, Object> out = new LinkedHashMap<>();
        int data = dataChunkOffset(wav);
        if (data < 0 || data + 2 > wav.length) {
            out.put("measured", false);
            out.put("note", "not a readable 16-bit PCM WAV — level not measured");
            return out;
        }
        long peak = 0;
        double sumSquares = 0;
        int n = 0;
        for (int i = data; i + 1 < wav.length; i += 2) {
            int s = (short) ((wav[i] & 0xFF) | (wav[i + 1] << 8));   // little-endian signed 16-bit
            int a = Math.abs(s);
            if (a > peak) {
                peak = a;
            }
            sumSquares += (double) s * s;
            n++;
        }
        if (n == 0) {
            out.put("measured", false);
            out.put("note", "no samples in the WAV data chunk");
            return out;
        }
        double rms = Math.sqrt(sumSquares / n);
        double peakDb = dbfs(peak);
        double rmsDb = dbfs(rms);
        boolean silent = peakDb <= SILENCE_DBFS;

        out.put("measured", true);
        out.put("samples", n);
        out.put("peakDbfs", round1(peakDb));
        out.put("rmsDbfs", round1(rmsDb));
        out.put("silent", silent);
        out.put("note", silent
                ? "DIGITAL SILENCE — the device delivered " + n + " samples that are all (near) zero. The "
                  + "capture worked; there was simply no sound on this input. Either nothing is playing on "
                  + "this machine, or the audio is going out through a DIFFERENT sink than the one bound."
                : "audio present (peak " + round1(peakDb) + " dBFS) — the device is carrying sound.");
        return out;
    }

    /** True when the clip is (near) digital silence. */
    public static boolean isSilent(byte[] wav) {
        Object silent = of(wav).get("silent");
        return Boolean.TRUE.equals(silent);
    }

    /** Byte offset of the WAV 'data' chunk payload, or -1. Walks the chunk list — never assumes 44 bytes. */
    private static int dataChunkOffset(byte[] w) {
        if (w == null || w.length < 12 || w[0] != 'R' || w[1] != 'I' || w[2] != 'F' || w[3] != 'F') {
            return -1;
        }
        int i = 12;                                   // past "RIFF"<size>"WAVE"
        while (i + 8 <= w.length) {
            int size = (w[i + 4] & 0xFF) | ((w[i + 5] & 0xFF) << 8)
                     | ((w[i + 6] & 0xFF) << 16) | ((w[i + 7] & 0xFF) << 24);
            if (w[i] == 'd' && w[i + 1] == 'a' && w[i + 2] == 't' && w[i + 3] == 'a') {
                return i + 8;
            }
            if (size < 0) {
                return -1;                            // corrupt header — refuse rather than read garbage
            }
            i += 8 + size + (size & 1);               // chunks are word-aligned
        }
        return -1;
    }

    private static double dbfs(double amplitude) {
        return amplitude <= 0 ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(amplitude / 32768.0);
    }

    private static Object round1(double d) {
        return Double.isInfinite(d) ? "-inf" : Math.round(d * 10.0) / 10.0;
    }
}
