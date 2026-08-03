#!/usr/bin/env bash
# muni-world UI diagnostic — run on the Pi, paste the WHOLE output here.
# It compares the SOURCE you pulled against what the RUNNING app actually serves, so we can tell instantly
# whether the problem is: (a) pull didn't land, (b) jar not rebuilt, (c) process not restarted, (d) browser.
cd "$(dirname "$0")/.."

echo "=== 1. git (are you on the latest commit?) ==="
git log --oneline -1
git rev-parse --abbrev-ref HEAD

echo; echo "=== 2. SOURCE index.html (what you pulled to disk) ==="
grep -o "UI build [A-Z0-9]*" muni-world/src/main/resources/static/index.html || echo "!! NO build marker in source — pull did not land"
echo -n "source has 'TV sources' heading: "; grep -c "TV sources" muni-world/src/main/resources/static/index.html

echo; echo "=== 3. built jar (is it fresh?) ==="
ls -la muni-world/build/libs/muni-world.jar 2>/dev/null || echo "!! no jar built"

echo; echo "=== 4. is muni actually running, and for how long? ==="
if [ -f logs/muni-world.pid ]; then
  pid=$(cat logs/muni-world.pid)
  ps -p "$pid" -o pid,etime,cmd 2>/dev/null || echo "!! pidfile says $pid but it's not alive"
else
  echo "no logs/muni-world.pid"
fi
pgrep -af "muni-world.jar" || echo "!! no running muni-world.jar process"

echo; echo "=== 5. SERVED page (what the running app returns RIGHT NOW) ==="
curl -s localhost:8090/ | grep -o "UI build [A-Z0-9]*" || echo "!! NO build marker in served page — the running jar is OLD"
echo -n "served 'TV sources' count: "; curl -s localhost:8090/ | grep -c "TV sources"
echo -n "served nav tabs: "; curl -s localhost:8090/ | grep -oE 'class="tab[^"]*" href="[^"]+">[^<]+' | sed -E 's/.*>//' | tr '\n' ' '; echo
echo -n "audio sources endpoint: "; curl -s localhost:8090/api/muni/audio/sources | head -c 160; echo

echo; echo "=== READ: section 2 = what's on disk, section 5 = what's served. If they differ, the jar/process is stale. ==="
