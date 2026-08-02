#!/usr/bin/env bash
# muni-world — one-time audio-capture setup for a Raspberry Pi / Debian-based Linux (ADR-0014).
# Installs ffmpeg + builds whisper.cpp + fetches a small English model, then helps you find the
# PulseAudio/PipeWire loopback ("monitor") that carries YouTube TV audio. Idempotent — safe to re-run.
#
# After this, enable the loop in muni-world with the env vars printed at the end. This captures a feed
# you are LICENSED to (your YouTube TV subscription), for private analysis — transcript-as-lead, never
# a number (ADR-0014). Nothing here redistributes audio.
set -euo pipefail

WHISPER_DIR="${WHISPER_DIR:-$HOME/whisper.cpp}"
MODEL="${MODEL:-base.en}"   # tiny.en = fastest on a Pi; base.en = better, still OK; small.en = slow on a Pi

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

say "1/4  Installing ffmpeg + build tools (needs sudo)"
sudo apt-get update -y
sudo apt-get install -y ffmpeg git build-essential cmake pulseaudio-utils curl

say "2/4  Building whisper.cpp in $WHISPER_DIR"
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

say "3/4  Fetching the $MODEL model"
bash ./models/download-ggml-model.sh "$MODEL"
MODEL_PATH="$WHISPER_DIR/models/ggml-$MODEL.bin"

say "4/4  Finding the audio loopback (monitor) source"
echo "Available PulseAudio/PipeWire sources (look for one ending in '.monitor'):"
pactl list sources short || true
MONITOR="$(pactl get-default-sink 2>/dev/null).monitor" || MONITOR=""
[ -n "$MONITOR" ] && echo "Default sink monitor looks like: $MONITOR"

cat <<EOF

Setup done. Verify capture works (play Bloomberg/CNBC on YouTube TV first), then run:

  # 10-second smoke test — record the loopback and play it back:
  ffmpeg -f pulse -i "${MONITOR:-<sink>.monitor}" -t 10 -ac 1 -ar 16000 /tmp/test.wav
  ffplay /tmp/test.wav    # you should hear the TV audio

Then start muni-world with the capture loop ON:

  export MUNI_AUDIO_CAPTURE=true
  export MUNI_WHISPER_BIN="$WHISPER_BIN"
  export MUNI_WHISPER_MODEL="$MODEL_PATH"
  export MUNI_AUDIO_DEVICE="pulse:${MONITOR:-<sink>.monitor}"
  export MUNI_AUDIO_FEED="bloomberg"      # or cnbc — labels the leads
  export MUNI_AUDIO_SECONDS=300           # chunk length; smaller = fresher, more overhead
  ./scripts/svc.sh start muni             # or: java -jar muni-world/build/libs/muni-world.jar

Leads appear on the muni-world page ("Audio leads") and at /api/muni/audio/leads/recent.
EOF
