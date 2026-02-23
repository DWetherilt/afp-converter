#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
usage: management/tools/restore_core_authenticity.sh \
  --encrypted <encrypted-file> \
  --meta <metadata-json> \
  --output <plaintext-json> \
  --project-db <project-state-sqlite> \
  --approval-event-id <governance-event-id> \
  --key <decryption-key>
EOF
}

encrypted=""
meta=""
output=""
project_db=""
approval_event_id=""
key=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --encrypted) encrypted="${2:-}"; shift 2 ;;
    --meta) meta="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --project-db) project_db="${2:-}"; shift 2 ;;
    --approval-event-id) approval_event_id="${2:-}"; shift 2 ;;
    --key) key="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$encrypted" || -z "$meta" || -z "$output" || -z "$project_db" || -z "$approval_event_id" || -z "$key" ]]; then
  usage
  exit 2
fi
if [[ ! -f "$encrypted" ]]; then
  echo "missing encrypted payload: $encrypted" >&2
  exit 2
fi
if [[ ! -f "$meta" ]]; then
  echo "missing metadata payload: $meta" >&2
  exit 2
fi
if [[ ! -f "$project_db" ]]; then
  echo "missing project db: $project_db" >&2
  exit 2
fi

approved_count="$(sqlite3 "$project_db" "select count(*) from governance_events where event_id='$(printf "%s" "$approval_event_id" | sed "s/'/''/g")' and lower(phase)='core_secret_restore' and lower(status) in ('approved','complete','completed');")"
if [[ "$approved_count" != "1" ]]; then
  echo "restore blocked: approval event not found or not approved for phase core_secret_restore: $approval_event_id" >&2
  exit 3
fi

mkdir -p "$(dirname "$output")"
tmp_out="${output}.tmp.$$"

openssl enc -d -aes-256-cbc -pbkdf2 \
  -in "$encrypted" \
  -out "$tmp_out" \
  -pass "pass:$key"

expected_sha="$(python3 - "$meta" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding='utf-8'))
print(data.get("integrity", {}).get("plaintextSha256", ""))
PY
)"
expected_size="$(python3 - "$meta" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding='utf-8'))
print(data.get("integrity", {}).get("plaintextSizeBytes", ""))
PY
)"

actual_sha="$(shasum -a 256 "$tmp_out" | awk '{print $1}')"
actual_size="$(wc -c "$tmp_out" | awk '{print $1}')"

if [[ -n "$expected_sha" && "$actual_sha" != "$expected_sha" ]]; then
  rm -f "$tmp_out"
  echo "restore blocked: plaintext SHA mismatch" >&2
  exit 4
fi
if [[ -n "$expected_size" && "$actual_size" != "$expected_size" ]]; then
  rm -f "$tmp_out"
  echo "restore blocked: plaintext size mismatch" >&2
  exit 4
fi

mv "$tmp_out" "$output"
echo "restored:$output"
