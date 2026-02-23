#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK_SRC="$ROOT_DIR/management/tools/git-hooks/pre-commit"
HOOK_DST="$ROOT_DIR/.git/hooks/pre-commit"

if [[ ! -f "$HOOK_SRC" ]]; then
  echo "missing hook template: $HOOK_SRC" >&2
  exit 1
fi
if [[ ! -d "$ROOT_DIR/.git/hooks" ]]; then
  echo "missing .git/hooks under $ROOT_DIR" >&2
  exit 1
fi

cp "$HOOK_SRC" "$HOOK_DST"
chmod +x "$HOOK_DST"
echo "installed:$HOOK_DST"
