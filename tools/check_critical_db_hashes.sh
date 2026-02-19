#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="$ROOT_DIR/pm/state/critical-db-sha256.txt"

critical=(
  "pm/state/application-realm.sqlite"
  "pm/state/pm-realm.sqlite"
  "pm/state/boilerplate-realm.sqlite"
  "pm/state/project-state.sqlite"
  "pm/state/boilerplate-state.sqlite"
  "pm/state/reasoning-experience.sqlite"
  "pm/state/reasoning-derived.sqlite"
)

write_baseline() {
  : > "$BASELINE"
  for p in "${critical[@]}"; do
    local abs="$ROOT_DIR/$p"
    [[ -f "$abs" ]] || { echo "missing:$p"; continue; }
    local sha
    sha=$(/usr/bin/shasum -a 256 "$abs" | /usr/bin/awk '{print $1}')
    echo "$sha  $p" >> "$BASELINE"
  done
  echo "baseline_written:$BASELINE"
}

check_baseline() {
  [[ -f "$BASELINE" ]] || { echo "baseline_missing:$BASELINE"; exit 1; }
  local ok=1
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    local expected path
    expected=$(echo "$line" | /usr/bin/awk '{print $1}')
    path=$(echo "$line" | /usr/bin/awk '{print $2}')
    local abs="$ROOT_DIR/$path"
    if [[ ! -f "$abs" ]]; then
      echo "missing:$path"
      ok=0
      continue
    fi
    local actual
    actual=$(/usr/bin/shasum -a 256 "$abs" | /usr/bin/awk '{print $1}')
    if [[ "$actual" != "$expected" ]]; then
      echo "mismatch:$path"
      ok=0
    fi
  done < "$BASELINE"

  if [[ $ok -eq 1 ]]; then
    echo "critical_db_hashes=PASS"
  else
    echo "critical_db_hashes=FAIL"
    exit 1
  fi
}

cmd="${1:-check}"
case "$cmd" in
  baseline) write_baseline ;;
  check) check_baseline ;;
  *) echo "usage: $0 [baseline|check]"; exit 2 ;;
esac
