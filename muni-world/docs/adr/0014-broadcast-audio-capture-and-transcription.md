# ADR-0014: Broadcast-audio capture and transcription as a soft-signal source

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Oleg
- **Tags:** audio, transcription, asr, soft-signal, sources, legal

## Context

Financial television (Bloomberg TV, CNBC, and muni-specific commentary) carries timely, unstructured
market colour — an issuer named, a downgrade or refunding mentioned, a muni/Treasury ratio quoted, a
headline hours before it reaches a filing. It is a **source** (ADR-0003), just in a modality we don't yet
ingest. The owner already **pays for the subscriptions**, so receiving the feed is licensed; the question
this ADR settles is how (and how far) we turn that audio into analysable data without breaking the two
disciplines the platform runs on: provenance and the number-guardrail.

This is architecturally significant — a new modality, a new external dependency (ASR), a legal surface, and
a new extraction stage — so it gets an ADR before code (design-first rule).

## Decision

Add **broadcast-audio capture + local transcription** as a source that feeds the existing ADR-0004 pipeline,
scoped deliberately narrow:

1. **Audio only, this phase.** Capture the **audio** of a feed the owner is licensed to. Video / lower-third
   ticker OCR is a harder, separate phase — **deferred** (see register), not in scope here.

2. **Capture is local and hardware-clean.** The preferred path is a line-out / HDMI-audio tap off the box
   already playing the feed, into a USB audio input on the Pi; system-audio loopback is the no-hardware
   fallback. `ffmpeg` records and segments into fixed chunks (e.g. 5-min Opus/WAV). Each chunk lands as an
   immutable `RawArtifact` with SHA-256 provenance (ADR-0005) — the audio is a landed artifact like any PDF.

3. **Transcription is the new extraction stage** (the audio analogue of ADR-0010). A **local** ASR
   (whisper.cpp on the Pi — the local SLM tier, ADR-0016 inherited) turns a landed audio artifact into a
   `Transcript`: time-stamped text segments, optional speaker turns, flagged as ASR-derived at a baseline
   confidence. Local keeps it cheap, private, and off any cloud. The raw audio is never mutated;
   transcription is versioned and re-runnable over the immutable store.

4. **Transcript is a LEAD, never a number.** This is the hard line. A transcript is narration; a spoken
   figure is **never** promoted to a canonical money/terms/risk value (jethro invariant 7 / ADR-0016,
   inherited; ADR-0011 quarantine). The transcript's job is to **point** the pipeline at something to then
   verify against a hard source — EMMA, an ACFR, refdata. Lead detection is **deterministic**: issuer-name
   matches against the catalog, CUSIP-like tokens to go resolve, muni keyword hits (downgrade, default,
   refunding, ratio, spread), each anchored to a transcript timestamp. Claude (ADR-0012) may *summarise or
   triage* a transcript window, grounded and cited — it may not assert a number from it.

5. **Legal posture (ADR-0008 inherited).** Capture is of a **licensed** feed, for **private** analysis.
   Audio and transcripts are **not redistributed** — internal-only, same polite/lawful posture as the rest
   of sourcing. Retention of raw audio is bounded (transcript + provenance is what we keep long-term).

## Consequences

- A whole timely modality becomes analysable data on hardware the owner already has, at no cloud cost.
- The number-guardrail is preserved by construction: audio can *surface* a lead but can never *set* a
  number — it always routes through hard-source verification, so a mis-heard figure can't corrupt canonical
  data.
- The build is small on top of what exists: an `AudioCaptureConnector` (capture → landed artifact) and a
  `Transcriber` stage feeding the normaliser/lead detector. Capture and ASR are abstracted behind interfaces
  so the pipeline is testable offline (the sandbox has no audio device or whisper binary; the Pi does).
- Deferred: **video/ticker-OCR** (a second phase), speaker **diarisation** quality, and **live/streaming**
  DRM'd capture (the analog/loopback tap sidesteps DRM and is the sanctioned path).

## Amendment (2026-08-09) — acquisition is a NETWORK STREAM, and only that

Point 2 above assumed a box already playing the feed. That is the whole cost: it needs a TV, a display, a
sound server and something actually playing, and when any of those is missing it records digital silence
while looking perfectly healthy — which is exactly what happened (a correct PulseAudio setup with no
playback stream). Acquisition is replaced, not supplemented; nothing else in this ADR changes.

- **A feed's `device` column is its STREAM**, in one of two forms: `yt:<page>` (a publisher's live page —
  `yt-dlp` resolves the current media URL **per capture**, because live CDN URLs expire and must never be
  stored) or `url:<stream>` (a direct HLS/DASH manifest). They are the same pipeline; `yt:` is `url:` with a
  resolve step in front.
- **The host tap is REMOVED**, not demoted: no `pulse:`/`alsa:` sources, no device scanner, no device
  picker. Keeping it as a fallback would keep the failure mode (and a second way to configure the same
  thing) alive for no benefit — the stream path needs no hardware at all.
- **The registry is read-only config that ships in the jar.** No host copy, no seeding, no merge, no
  "bind" write path, and no UI that edits it. Each of those caused a failure the owner had to debug: a
  tracked file that aborted `git pull`, a live copy that ignored a newly shipped stream, and two template
  copies where editing the wrong one changed nothing. To change a feed: edit the CSV, rebuild.
- **Dependency added:** `yt-dlp` on the capture host (`svc.sh setup tv` installs it; `MUNI_YTDLP_BIN` pins
  the path, since a background process's PATH may exclude `~/.local/bin`). A missing or stale yt-dlp is
  reported as a **gate** on the status endpoint, not only as a failed capture.
- **A failing feed backs off** (30 s doubling to 5 min) instead of retrying at the loop's ~1 s gap: a
  capture that fails instantly does not pace the loop the way a real one does.
- **Legal posture unchanged** (point 5): the publisher's own published stream, recorded for private
  analysis, never redistributed, and subject to that publisher's and platform's terms.
