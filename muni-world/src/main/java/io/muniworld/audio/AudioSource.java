package io.muniworld.audio;

/**
 * One registered TV/audio feed (ADR-0014) — the audio analogue of an ADR-0003 registered source. The feed
 * list is a registry, not a single env var: it grows, and each entry is configured the same way as the
 * market-data / social sources. {@code device} binds the feed to a host audio input
 * ({@code "<ffmpeg-format>:<name>"}, e.g. {@code pulse:default.monitor}); a feed is only captured when it is
 * {@code enabled} AND has a device bound (so a listed-but-unbound feed shows in the UI without capturing).
 *
 * @param id           stable id (e.g. {@code tv-bloomberg}) — used as the leads' {@code feed}/source id
 * @param label        display name (e.g. {@code Bloomberg TV})
 * @param publisher    who publishes it (e.g. {@code Bloomberg})
 * @param category     source category (e.g. {@code tv}, {@code radio}, {@code podcast})
 * @param device       host audio input, or blank until bound on this host
 * @param chunkSeconds capture chunk length for this feed
 * @param enabled      whether the capture loop should record this feed
 */
public record AudioSource(String id, String label, String publisher, String category,
                          String device, int chunkSeconds, boolean enabled) {

    /** Capturable only when enabled AND bound to a host device — otherwise it's just a catalog entry. */
    public boolean capturable() {
        return enabled && device != null && !device.isBlank();
    }
}
