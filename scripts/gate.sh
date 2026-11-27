#!/usr/bin/env bash
# Verification gate with a hang watchdog. Runs the `gate` Gradle task (the same
# one CI runs) in the background and kills the run if the log goes silent for
# GATE_SILENT_SEC seconds or the run exceeds GATE_HARD_SEC seconds in total.
#
# Why: this machine has wedged on Gradle runs more than once — a test JVM once
# spun for 37 minutes at 240% CPU without writing results (a fake's background
# loop that never suspended). Poll a log from the background instead of blocking
# on a foreground Gradle run; if a retry ever hits "Timeout waiting to lock",
# stop leftover daemons (./gradlew --stop) and delete the stale .gradle lock
# files before rerunning.
#
# Knobs (all env vars, no edits needed):
#   GATE_WORKERS     Gradle workers (default 2 — this box wedges at full parallelism)
#   GATE_SILENT_SEC  kill after this many seconds without new log output (default 480)
#   GATE_HARD_SEC    absolute ceiling for the whole run (default 3600)

set -u
cd "$(dirname "$0")/.."

LOG_DIR="${TMPDIR:-/tmp}/grack-gate"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/gate-$(date +%Y%m%d-%H%M%S).log"
WORKERS="${GATE_WORKERS:-2}"
SILENT_SEC="${GATE_SILENT_SEC:-480}"
HARD_SEC="${GATE_HARD_SEC:-3600}"

echo "gate: logging to $LOG (workers=$WORKERS, kill after ${SILENT_SEC}s silent or ${HARD_SEC}s total)"
./gradlew gate --max-workers="$WORKERS" --no-daemon >"$LOG" 2>&1 &
pid=$!

start=$(date +%s)
last_size=0
last_change=$start
while kill -0 "$pid" 2>/dev/null; do
  sleep 30
  now=$(date +%s)
  size=$(stat -c %s "$LOG" 2>/dev/null || echo 0)
  if [ "$size" != "$last_size" ]; then
    last_size=$size
    last_change=$now
  fi
  if [ $((now - last_change)) -ge "$SILENT_SEC" ]; then
    echo "gate: no log output for ${SILENT_SEC}s — killing the wedged run (log: $LOG)"
    kill "$pid" 2>/dev/null
    sleep 5
    kill -9 "$pid" 2>/dev/null
    ./gradlew --stop >/dev/null 2>&1 || true
    exit 1
  fi
  if [ $((now - start)) -ge "$HARD_SEC" ]; then
    echo "gate: exceeded ${HARD_SEC}s total — killing (log: $LOG)"
    kill "$pid" 2>/dev/null
    sleep 5
    kill -9 "$pid" 2>/dev/null
    ./gradlew --stop >/dev/null 2>&1 || true
    exit 1
  fi
done

wait "$pid"
status=$?
tail -n 20 "$LOG"
echo "gate: exit $status (full log: $LOG)"
exit $status
