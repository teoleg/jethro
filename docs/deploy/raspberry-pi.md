# Running Jethro on a Raspberry Pi 4 (Debian / Pi OS 64-bit)

This is the full build-and-run runbook for a Pi 4. Everything in the current build
(`trading-core`, Redpanda, Postgres, Ollama SLM, the attention feed) runs on ARM64 — the
LMDB native (`aarch64-linux-gnu.so`), Redpanda, Postgres, and Ollama all ship ARM64
builds. The only real constraints are **RAM and I/O**, addressed below.

## 0. Honest expectations first

| Pi 4 RAM | Verdict |
|---|---|
| **8 GB** | Recommended. Full stack + a 0.5B–1B model runs comfortably. |
| **4 GB** | Workable with the 0.5B model and swap; don't run a 3B model. |
| **2 GB** | Run the app + Redpanda + Postgres, but disable Ollama (`jethro.ai.enabled=false`). Inference won't fit. |

Two hard requirements:

- **64-bit OS.** Pi OS (64-bit) or Debian arm64. The JVM and the LMDB native are 64-bit
  only — a 32-bit OS will not start the app. Check with `uname -m` → must print `aarch64`.
- **Use a USB SSD, not an SD card, if you can.** Docker images, Ollama model files, and
  swap all hammer storage; SD cards are slow and wear out. An SSD turns "painful" into
  "fine."

The SLM is CPU-only on a Pi — commentary that takes ~1s on a laptop may take 10–30s here.
That's fine: the market path never waits on inference (invariant 7), and the commentator
degrades gracefully if the model is slow or down. Just widen its interval and timeout
(step 7).

## 1. OS and packages

```bash
uname -m                      # must be: aarch64
sudo apt update && sudo apt full-upgrade -y
sudo apt install -y git curl unzip
```

## 2. Java 21 (Temurin, ARM64)

Debian 12 / Pi OS bookworm ship OpenJDK 17; the project needs 21. Use Adoptium:

```bash
sudo apt install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb \
  $(awk -F= '/VERSION_CODENAME/{print $2}' /etc/os-release) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-21-jdk
java -version                 # openjdk version "21..."
```

(Debian 13 / trixie: `sudo apt install openjdk-21-jdk` works directly, skip Adoptium.)

## 3. Docker (ARM64)

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
# log out and back in (or `newgrp docker`) so the group takes effect
docker run --rm hello-world    # sanity check
```

## 4. Swap and memory headroom (Pi-specific)

Give the Pi breathing room so a memory spike doesn't OOM-kill a container. On an SSD,
2 GB of swap is reasonable:

```bash
sudo dphys-swapfile swapoff
sudo sed -i 's/^CONF_SWAPSIZE=.*/CONF_SWAPSIZE=2048/' /etc/dphys-swapfile
sudo dphys-swapfile setup && sudo dphys-swapfile swapon
free -h
```

If you run headless, disable the desktop GUI to reclaim ~300–400 MB:
`sudo systemctl set-default multi-user.target` (reboot to apply).

## 5. Get the code

```bash
git clone https://github.com/teoleg/jethro.git && cd jethro
git checkout claude/new-session-smb8v6
```

## 6. Build

```bash
./gradlew build               # first run downloads Gradle 8.14.3 + deps; slower on a Pi
```

Expect a few minutes on first build (JIT + downloads). `BUILD SUCCESSFUL` with the unit
and module tests green means the ARM64 toolchain, LMDB native, and Avro codegen all work.
If the Pi is RAM-tight during the build, cap the Gradle daemon:
`echo "org.gradle.jvmargs=-Xmx1g" >> gradle.properties`.

## 7. Pi-tuned configuration

The defaults target a laptop (3B model, 60s interval). Override for the Pi — create
`app/src/main/resources/application-pi.properties`:

```properties
# smaller, faster model; give slow CPU inference room and breathe less often
jethro.ai.model=qwen2.5:0.5b
jethro.ai.interval-seconds=180
jethro.ai.request-timeout-seconds=120
# 2 sim instruments instead of 4 to lighten the load (optional)
jethro.trading.sim-instruments=AAPL,MSFT
```

Then run with the `pi` profile (step 9). On a **2 GB** Pi, also set
`jethro.ai.enabled=false` and skip Ollama entirely.

Redpanda is the other memory user. The repo's `docker-compose.yml` already pins it to
`--smp 1 --memory 1G` (dev-container mode). On a 4 GB Pi that's fine alongside the 0.5B
model; no change needed.

## 8. Start infra

```bash
docker compose up -d redpanda postgres ollama      # ARM64 images pull automatically
docker compose ps                                  # wait for healthy

# pull the small model once (~400 MB for 0.5b)
docker exec jethro-ollama-1 ollama pull qwen2.5:0.5b
# sanity-check the model alone before the app uses it:
docker exec jethro-ollama-1 ollama run qwen2.5:0.5b "say hi in three words"
```

On 2 GB (no Ollama): `docker compose up -d redpanda postgres`.

## 9. Run the app

```bash
SPRING_PROFILES_ACTIVE=pi ./gradlew :app:bootRun
```

Watch for:

```
trading-core started: 2 instruments, sim seed 42 ...
Tomcat started on port 8080
ui-gateway consuming md.marks and ai.decisions from localhost:9092
commentary: ...            # appears after the first model run (slow on a Pi — be patient)
```

Then open **http://\<pi-address\>:8080** from another machine on your LAN — the live marks
strip, the attention feed, and `/books.html` all work. (`bootRun` binds all interfaces by
default; if you firewalled the Pi, allow TCP 8080.)

## 10. Run as a container instead (optional, survives reboots)

```bash
docker compose --profile app up -d --build          # builds an ARM64 image and runs everything
```

The `Dockerfile` already passes `-XX:+UseZGC` and the LMDB `--add-opens` flags. To keep it
running across reboots, add `restart: unless-stopped` to the services (or use the systemd
approach in step 11).

## 11. Autostart on boot (headless appliance)

Create `/etc/systemd/system/jethro.service`:

```ini
[Unit]
Description=Jethro trading platform
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/home/pi/jethro
ExecStart=/usr/bin/docker compose --profile app up -d --build
ExecStop=/usr/bin/docker compose --profile app down
User=pi

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now jethro.service
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| App exits at start, `UnsatisfiedLinkError` mentioning lmdb | Not 64-bit OS. `uname -m` must be `aarch64`. Reflash Pi OS 64-bit. |
| `java.lang.reflect.InaccessibleObjectException` on LMDB | The `--add-opens` flags are missing — use `./gradlew :app:bootRun` (they're wired in) or the provided Dockerfile, not a hand-rolled `java -jar`. |
| A container is `Killed` / restarts | Out of memory. Drop to the 0.5B model, lower `sim-instruments`, add swap (step 4), or disable Ollama on a 2 GB Pi. |
| No commentary cards appear | Model still warming up (first Pi inference is slow) or Ollama not reachable. Check `docker logs jethro-ollama-1`; the feed's marks and stale-mark alerts work regardless. |
| Redpanda won't go healthy | Give it a minute on a Pi; if it OOMs, lower `--memory` to `700M` in `docker-compose.yml`. |
| SD card wearing out / very slow | Move to a USB SSD; point Docker's data-root and swap there. |

## Memory budget (rough, 0.5B model)

| Component | ~RAM |
|---|---|
| JVM app (ZGC, 2 instruments) | 300–500 MB |
| Redpanda (`--memory 1G`) | ~1 GB |
| Postgres | ~100 MB |
| Ollama + qwen2.5:0.5b | ~1–1.5 GB |
| **Total** | **~3 GB** → comfortable on 8 GB, tight on 4 GB, use 2 GB without Ollama |
