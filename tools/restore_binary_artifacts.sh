#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POLICY_FILE="$ROOT_DIR/pm/workflow/binary-artifact-policy.txt"
RESTORE_REF="${RESTORE_REF:-HEAD~1}"
STRICT_MODE="${STRICT_MODE:-false}"
REQUIRE_CONDITIONAL="${REQUIRE_CONDITIONAL:-false}"

if [[ ! -f "$POLICY_FILE" ]]; then
  echo "missing policy file: $POLICY_FILE" >&2
  exit 1
fi

strict=0
strict_mode_lc="$(printf '%s' "$STRICT_MODE" | tr '[:upper:]' '[:lower:]')"
case "$strict_mode_lc" in
  1|true|yes) strict=1 ;;
esac

require_conditional=0
require_conditional_lc="$(printf '%s' "$REQUIRE_CONDITIONAL" | tr '[:upper:]' '[:lower:]')"
case "$require_conditional_lc" in
  1|true|yes) require_conditional=1 ;;
esac

restore_one() {
  local path="$1"
  local source="$2"
  local required="$3"
  local effective_required="$required"

  if [[ "$required" == "conditional" && $require_conditional -eq 1 ]]; then
    effective_required="true"
  fi

  local abs="$ROOT_DIR/$path"
  if [[ -f "$abs" ]]; then
    echo "present:$path"
    return 0
  fi

  case "$source" in
    git|reference)
      if git -C "$ROOT_DIR" checkout "$RESTORE_REF" -- "$path" >/dev/null 2>&1; then
        echo "restored:$path:from=$RESTORE_REF"
        return 0
      fi
      ;;
    user_or_generator|generated)
      ;;
  esac

  echo "unresolved:$path:source=$source:required=$required:effective_required=$effective_required"
  if [[ "$effective_required" == "true" && $strict -eq 1 ]]; then
    return 1
  fi
  return 0
}

status=0
while IFS= read -r line; do
  [[ -n "$line" ]] || continue
  [[ "$line" =~ ^# ]] && continue
  path="${line%%|*}"
  meta="${line#*|}"
  source="$(echo "$meta" | awk -F'|' '{print $1}' | sed 's/^source=//')"
  required="$(echo "$meta" | awk -F'|' '{print $2}' | sed 's/^required=//')"

  if ! restore_one "$path" "$source" "$required"; then
    status=1
  fi
done < "$POLICY_FILE"

if [[ $status -ne 0 ]]; then
  echo "binary_restore=FAIL"
  exit 1
fi
echo "binary_restore=PASS"
