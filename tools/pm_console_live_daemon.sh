#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/pm/state/pmconsole-live.pid"
LOG_FILE="$ROOT_DIR/pm/state/pmconsole-live.log"
INTERVAL_SECONDS="${PMCONSOLE_LIVE_INTERVAL_SECONDS:-5}"
TOP_DECISIONS="${PMCONSOLE_LIVE_TOP:-7}"
PMCONSOLE_BIN="$ROOT_DIR/pm-console/build/install/pmconsole/bin/pmconsole"

cmd="${1:-status}"

mkdir -p "$ROOT_DIR/pm/state"

is_running() {
  if [[ ! -f "$PID_FILE" ]]; then
    return 1
  fi
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -z "$pid" ]]; then
    return 1
  fi
  kill -0 "$pid" 2>/dev/null
}

ensure_bin() {
  if [[ -x "$PMCONSOLE_BIN" ]]; then
    return 0
  fi
  (cd "$ROOT_DIR" && ./gradlew --no-daemon :pm-console:installDist >/dev/null)
}

start_live() {
  if is_running; then
    echo "pmconsole-live:already-running:pid=$(cat "$PID_FILE")"
    return 0
  fi
  ensure_bin
  : > "$LOG_FILE"
  nohup bash -lc "tail -f /dev/null | \"$PMCONSOLE_BIN\" live --interval=\"$INTERVAL_SECONDS\" --top=\"$TOP_DECISIONS\"" >>"$LOG_FILE" 2>&1 &
  local pid=$!
  echo "$pid" > "$PID_FILE"
  sleep 0.2
  if kill -0 "$pid" 2>/dev/null; then
    echo "pmconsole-live:started:pid=$pid:log=${LOG_FILE#$ROOT_DIR/}"
    return 0
  fi
  echo "pmconsole-live:failed-to-start" >&2
  return 1
}

stop_live() {
  if ! is_running; then
    rm -f "$PID_FILE"
    echo "pmconsole-live:not-running"
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  kill "$pid" 2>/dev/null || true
  sleep 0.2
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  echo "pmconsole-live:stopped:pid=$pid"
}

status_live() {
  if is_running; then
    echo "pmconsole-live:running:pid=$(cat "$PID_FILE"):log=${LOG_FILE#$ROOT_DIR/}"
  else
    echo "pmconsole-live:stopped"
  fi
}

case "$cmd" in
  start) start_live ;;
  stop) stop_live ;;
  restart) stop_live; start_live ;;
  status) status_live ;;
  *)
    echo "Usage: $0 [start|stop|restart|status]" >&2
    exit 2
    ;;
esac
