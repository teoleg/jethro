# Runbook — broadcast-audio capture on the Pi (ADR-0014)

Capture the audio of **Bloomberg TV / CNBC** from your **YouTube TV** subscription on the Pi, transcribe it
locally, and surface **leads to verify** (issuer named, muni keyword, CUSIP token) on the muni-world page.
This captures a feed you are **licensed** to, for **private** analysis. A transcript is a **lead, never a
number** (ADR-0014) — nothing spoken sets a canonical value.

Cost: **$0**. YouTube TV plays through the Pi's audio, so we capture the software **loopback (monitor)** —
no hardware to buy.

## What runs where

```
YouTube TV in a browser (Bloomberg/CNBC)  →  PulseAudio/PipeWire ".monitor"
        →  ffmpeg records a chunk  →  whisper.cpp transcribes (local)
        →  deterministic lead detection  →  muni-world "Audio leads"
```

## Step by step

1. **One-time setup** — installs ffmpeg, builds whisper.cpp, fetches a model, finds your loopback:
   ```bash
   cd ~/jethro/muni-world
   bash scripts/setup-audio-pi.sh          # MODEL=tiny.en for a faster (rougher) run on a Pi
   ```
   Note the `MUNI_WHISPER_BIN`, `MUNI_WHISPER_MODEL`, and `pulse:<sink>.monitor` values it prints.

2. **Tune the feed.** Open YouTube TV in a browser on the Pi and start **Bloomberg TV** (or CNBC). The
   monitor captures whatever is currently playing out the Pi's audio — so one feed at a time per host.

3. **Smoke-test the loopback** (should play back the TV audio):
   ```bash
   ffmpeg -f pulse -i <sink>.monitor -t 10 -ac 1 -ar 16000 /tmp/test.wav && ffplay /tmp/test.wav
   ```
   Silent? Pick the right monitor from `pactl list sources short` (the one ending in `.monitor` for the
   sink your browser plays into).

4. **Configure the feed registry + start capture.** Feeds are a registry (like the market-data/social
   sources), not env vars. Set the config once in `scripts/jethro.env` (copy from `jethro.env.example`):
   ```bash
   MUNI_WHISPER_BIN=~/whisper.cpp/build/bin/whisper-cli
   MUNI_WHISPER_MODEL=~/whisper.cpp/models/ggml-base.en.bin
   MUNI_AUDIO_SOURCES_FILE=muni-world/seeds/audio-sources.csv
   ```
   Then edit the registry `muni-world/seeds/audio-sources.csv` (pipe-delimited) — bind a feed to your
   loopback device and enable it:
   ```
   tv-bloomberg|Bloomberg TV|Bloomberg|tv|pulse:<sink>.monitor|300|true|
   ```
   Turn capture on (flips the master switch + restarts muni-world):
   ```bash
   ./scripts/svc.sh tv start
   ./scripts/svc.sh tv status     # shows the registry + recent leads
   ```

5. **Watch the leads** — the muni-world page ("Audio leads") polls every 15s, or:
   ```bash
   curl -s localhost:8090/api/muni/audio/leads/recent?limit=20 | jq
   ```

## Notes & limits

- **Pi speed.** whisper.cpp on a Pi is CPU-bound. `base.en` runs near real-time on a Pi 5, slower on a Pi 4;
  drop to `tiny.en` if transcription falls behind. The loop is single-threaded, so a slow model just paces
  capture (chunks never overlap) at the cost of lag — it never drops silently.
- **One feed per host.** The monitor carries whatever's playing. To run Bloomberg **and** CNBC at once you'd
  need two audio sinks (two browser profiles routed to separate sinks) and two capture processes — deferred.
- **Guardrail.** Leads are pointers to verify against hard sources (EMMA/ACFR/refdata). A spoken "5%" or
  "$300 million" is never captured as a coupon/size — only as context on a keyword lead.
- **Off by default.** `MUNI_AUDIO_CAPTURE` unset ⇒ the loop bean isn't created; the jar boots identically in
  CI/sandbox (no audio device). Only the Pi turns it on.
