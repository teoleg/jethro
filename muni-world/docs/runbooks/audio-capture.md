# Runbook — broadcast-audio capture (ADR-0014)

Capture the audio of a **live broadcast stream** (Bloomberg TV via its official YouTube live channel),
transcribe it locally, and surface **leads to verify** (issuer named, muni keyword, CUSIP token) on the
muni-world TV page. A transcript is a **lead, never a number** (ADR-0014) — nothing spoken sets a
canonical value. Recording is for **private analysis only** and remains subject to the broadcaster's and
platform's terms.

## How it works — one path, no browser, no sound card

```
seeds/audio-sources.csv (yt:<live page>)
  → yt-dlp resolves the CURRENT stream URL   (per capture — live CDN URLs expire)
  → ffmpeg pulls the audio chunk straight off the CDN
  → whisper.cpp transcribes locally
  → deterministic lead detection → "Audio leads" on /tv.html
```

There is **no host-audio / PulseAudio loopback step** — earlier versions captured a browser playing into
the Pi's sound stack; that path was removed ("connect and stream", nothing else). The Pi needs no browser,
no audio device, and nothing playing.

## The feed registry (read-only, ships in the jar)

`src/main/resources/seeds/audio-sources.csv` — edit and rebuild; there is no writable host copy. The
`device` field is the source and takes two forms:

- `yt:<page>` — a live **page** whose current media URL yt-dlp resolves at each capture.
  **Use a channel's `/live` URL, never a `watch?v=` video id** — a video id points at ONE broadcast and
  dies with it ("This live stream recording is not available", exactly how the first attempt failed).
  Shipped default: `yt:https://www.youtube.com/@markets/live` (Bloomberg Television's official channel).
- `url:<stream>` — a direct HLS/DASH/Icecast URL; ffmpeg reads it straight.

## Host prerequisites (the capture host only — nothing runs in CI/sandbox)

One command installs everything below and writes the paths into `local.env`:

```bash
scripts/svc.sh setup tv
```

Or by hand:

1. **ffmpeg** — `apt install ffmpeg`.
2. **yt-dlp, current** — a stale build cannot resolve today's YouTube:
   `python3 -m pip install -U yt-dlp` (the app checks the version date and refuses a stale one loudly).
3. **A JS runtime for yt-dlp** (YouTube requires it for stream resolution) — `deno` is the light option.
4. **whisper.cpp** + a model (e.g. `small.en`; `tiny.en` for a faster, rougher Pi run).

Config in `local.env` (all read at boot; see `application.properties` for the full list):

```bash
MUNI_WHISPER_BIN=~/whisper.cpp/build/bin/whisper-cli
MUNI_WHISPER_MODEL=~/whisper.cpp/models/ggml-small.en.bin
# optional: MUNI_YTDLP_BIN=~/.local/bin/yt-dlp
```

## Operate

```bash
scripts/svc.sh start tv      # flips MUNI_AUDIO_CAPTURE=true in local.env + bounces muni-world
scripts/svc.sh stop tv       # capture off; muni-world keeps running
scripts/svc.sh status tv     # capture flags + recent leads
```

Then open `http://<host>:8090/tv.html` — capture state per feed, last transcript chunks, and the leads
table. A feed that fails (stream down, resolver refused) backs off exponentially (30s → 5min cap) and says
why on the page; it never busy-loops.

## Troubleshooting

- **"This live stream recording is not available"** — the registry row points at a `watch?v=` id; use the
  channel `/live` URL (see registry comments).
- **"No supported JavaScript runtime"** — install deno (prerequisite 3).
- **Resolver refused / no formats** — update yt-dlp (prerequisite 2); the page shows the exact resolver
  message rather than a generic failure.
- Chunks land, no leads: leads are deterministic keyword/CUSIP hits — quiet market talk produces none;
  check the transcript pane to confirm transcription itself is running.
