#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEEP_FILE="$ROOT_DIR/management/pm/workflow/minimal-snapshot-keep.txt"
cd "$ROOT_DIR"

[[ -f "$KEEP_FILE" ]] || { echo "missing keep file: $KEEP_FILE"; exit 1; }

mapfile -t keep_paths < <(grep -v '^#' "$KEEP_FILE" | sed '/^[[:space:]]*$/d')

keep_contains() {
  local p="$1"
  for k in "${keep_paths[@]}"; do
    local k_norm="${k%/}"
    if [[ "$p" == "$k_norm" ]] || [[ "$p" == "$k_norm"/* ]] || [[ "$k_norm" == "$p"/* ]]; then
      return 0
    fi
  done
  return 1
}

while IFS= read -r f; do
  if keep_contains "$f"; then
    continue
  fi
  rm -f -- "$f"
done < <(find . -type f -not -path './.git/*' | sed 's#^\./##')

changed=1
while [[ $changed -eq 1 ]]; do
  changed=0
  while IFS= read -r d; do
    [[ -n "$d" ]] || continue
    if keep_contains "$d"; then
      continue
    fi
    if rmdir -- "$d" 2>/dev/null; then
      changed=1
    fi
  done < <(find . -depth -type d -not -path './.git/*' | sed 's#^\./##' | sort -r)
done

echo "minimal_snapshot_complete"
find . -type f -not -path './.git/*' | sort
