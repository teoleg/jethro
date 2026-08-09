#!/usr/bin/env bash
# muni-world — one-time TV-audio setup for a Raspberry Pi / Debian-based Linux (ADR-0014).
#
# The capture path is ONLINE: ffmpeg pulls the stream over the network, whisper.cpp transcribes it.
# No TV, no HDMI, no browser, no sound card, no display — it works headless and unattended, which is the
# whole point. This script installs what that needs:
#
#   ffmpeg     reads the stream, writes 16 kHz mono PCM WAV (whisper's ONLY input format)
#   yt-dlp     resolves a YouTube live page to the current media URL (those URLs expire, so this runs
#              per capture; the registry stores the PAGE)
#   whisper.cpp + a small English model — the transcriber
#
# PulseAudio discovery at the end is the OPTIONAL fallback, for capturing what this box itself plays.
# Idempotent — safe to re-run. Recording is for private analysis (transcript-as-lead, never a number)
# and remains subject to the publisher's terms. Nothing here redistributes audio.
set -euo pipefail

WHISPER_DIR="${WHISPER_DIR:-$HOME/whisper.cpp}"
# NOTE: NOT named MODEL — that collides with jethro's Ollama MODEL (e.g. qwen2.5:3b) exported from local.env.
# whisper models are tiny/base/small/etc. tiny.en = fastest on a Pi; base.en = better; small.en = slow.
WHISPER_MODEL_NAME="${WHISPER_MODEL_NAME:-base.en}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

say "1/5  Installing ffmpeg + build tools (needs sudo)"
sudo apt-get update -y
sudo apt-get install -y ffmpeg git build-essential cmake pulseaudio-utils curl python3 python3-pip

say "2/5  Installing yt-dlp (resolves the live stream URL each capture)"
# Prefer pip: the apt package is usually months old, and a stale yt-dlp is the single most common reason
# a YouTube live page stops resolving. --user keeps it out of the system python.
python3 -m pip install --user --upgrade yt-dlp 2>/dev/null \
  || python3 -m pip install --user --upgrade --break-system-packages yt-dlp \
  || sudo apt-get install -y yt-dlp
YTDLP_BIN="$(command -v yt-dlp || echo "$HOME/.local/bin/yt-dlp")"
if [ -x "$YTDLP_BIN" ]; then
  echo "yt-dlp: $YTDLP_BIN ($("$YTDLP_BIN" --version 2>/dev/null || echo 'version unknown'))"
else
  echo "!!  yt-dlp did not install — the yt: source kind will fail loudly until it does."
fi

say "3/5  Building whisper.cpp in $WHISPER_DIR"
if [ ! -d "$WHISPER_DIR" ]; then
  git clone https://github.com/ggerganov/whisper.cpp "$WHISPER_DIR"
fi
cd "$WHISPER_DIR"
git pull --ff-only || true
cmake -B build >/dev/null
cmake --build build --config Release -j"$(nproc)"
# whisper.cpp's CLI binary is 'whisper-cli' (older builds: 'main'); expose a stable path either way.
WHISPER_BIN="$WHISPER_DIR/build/bin/whisper-cli"
[ -x "$WHISPER_BIN" ] || WHISPER_BIN="$WHISPER_DIR/build/bin/main"

say "4/5  Fetching the $WHISPER_MODEL_NAME whisper model"
bash ./models/download-ggml-model.sh "$WHISPER_MODEL_NAME"
MODEL_PATH="$WHISPER_DIR/models/ggml-$WHISPER_MODEL_NAME.bin"

say "5/5  (optional) Host audio inputs — only needed to capture what THIS box plays"
pactl list sources short 2>/dev/null || echo "(no PulseAudio/PipeWire here — fine, the online path does not use it)"

# Write the resolved paths into the env file (svc.sh passes MUNI_ENV_FILE) so no vars are left empty.
# The ABSOLUTE yt-dlp path matters: muni-world runs as a background process whose PATH may not include
# ~/.local/bin, so a bare "yt-dlp" would resolve interactively and fail in the service.
if [ -n "${MUNI_ENV_FILE:-}" ]; then
  set_kv() {  # key value → set/replace in the env file
    if grep -qE "^$1=" "$MUNI_ENV_FILE" 2>/dev/null; then
      sed -i.bak -E "s|^$1=.*|$1=$2|" "$MUNI_ENV_FILE" && rm -f "$MUNI_ENV_FILE.bak"
    else
      echo "$1=$2" >> "$MUNI_ENV_FILE"
    fi
  }
  set_kv MUNI_WHISPER_BIN "$WHISPER_BIN"
  set_kv MUNI_WHISPER_MODEL "$MODEL_PATH"
  [ -x "$YTDLP_BIN" ] && set_kv MUNI_YTDLP_BIN "$YTDLP_BIN"
  say "wrote MUNI_WHISPER_BIN, MUNI_WHISPER_MODEL and MUNI_YTDLP_BIN into $MUNI_ENV_FILE"
fi

cat <<EOF

Setup done. There is NOTHING to bind: the registry ships with Bloomberg Television's live stream already
configured, so the next step is simply to turn capture on:

  ./scripts/svc.sh start tv
  ./scripts/svc.sh status tv      # feed registry + recent leads

To check it end-to-end right now, open the TV page (http://<this-host>:8090/tv.html) and press
"Test 8s" on the Bloomberg row — it records a few seconds off the live stream and prints what it heard.

Command-line equivalent, if you want to see the raw pieces:

  $YTDLP_BIN -f bestaudio -g "https://www.youtube.com/watch?v=iyOq8DhaMYw"   # → a media URL
  ffmpeg -i "<that url>" -t 10 -vn -ac 1 -ar 16000 -c:a pcm_s16le /tmp/test.wav
  $WHISPER_BIN -m "$MODEL_PATH" -f /tmp/test.wav

Leads appear on the muni-world TV page ("Audio leads") and at /api/muni/audio/leads/recent.
EOF
