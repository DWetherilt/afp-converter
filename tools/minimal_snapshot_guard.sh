#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEEP_FILE="$ROOT_DIR/pm/workflow/minimal-snapshot-keep.txt"

[[ -f "$KEEP_FILE" ]] || { echo "missing keep file: $KEEP_FILE"; exit 1; }

status=0
while IFS= read -r entry; do
  [[ -n "$entry" ]] || continue
  [[ "$entry" =~ ^# ]] && continue
  path="${entry%/}"
  if [[ ! -e "$ROOT_DIR/$path" ]]; then
    echo "missing_keep_entry:$path"
    status=1
  fi
done < "$KEEP_FILE"

if [[ $status -eq 0 ]]; then
  echo "minimal_snapshot_guard=PASS"
else
  echo "minimal_snapshot_guard=FAIL"
  exit 1
fi
