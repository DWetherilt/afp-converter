#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_DIR="$ROOT_DIR/management/pm/checkpoints/recovery-doctor-local"

latest_checkpoint() {
  [[ -d "$BASE_DIR" ]] || return 1
  ls -1 "$BASE_DIR" 2>/dev/null | sort | tail -n 1
}

restore_from() {
  local checkpoint="$1"
  local cp_dir="$BASE_DIR/$checkpoint"
  [[ -d "$cp_dir" ]] || { echo "missing checkpoint: $cp_dir" >&2; return 1; }

  local restored=0
  for f in \
    project-plan-progress-data.json \
    issues-log-tickle.json \
    issues-effectiveness.json
  do
    if [[ -f "$cp_dir/$f" ]]; then
      cp "$cp_dir/$f" "$ROOT_DIR/management/pm/reports/$f"
      restored=$((restored+1))
    fi
  done

  echo "restored_reports:$restored:checkpoint=$checkpoint"
}

checkpoint="${1:-}"
if [[ -z "$checkpoint" ]]; then
  checkpoint="$(latest_checkpoint || true)"
fi

if [[ -z "$checkpoint" ]]; then
  echo "no recovery-doctor checkpoint found under $BASE_DIR" >&2
  exit 1
fi

restore_from "$checkpoint"
