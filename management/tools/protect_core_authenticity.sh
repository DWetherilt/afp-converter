#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
usage: management/tools/protect_core_authenticity.sh \
  --input <plaintext-json> \
  --output <encrypted-file> \
  --meta <metadata-json> \
  --index <index-json> \
  --project-db <project-state-sqlite> \
  --knowledge-id <knowledge-id> \
  --key <decryption-key>
EOF
}

input=""
output=""
meta=""
index=""
project_db=""
knowledge_id=""
key=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) input="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --meta) meta="${2:-}"; shift 2 ;;
    --index) index="${2:-}"; shift 2 ;;
    --project-db) project_db="${2:-}"; shift 2 ;;
    --knowledge-id) knowledge_id="${2:-}"; shift 2 ;;
    --key) key="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$input" || -z "$output" || -z "$meta" || -z "$index" || -z "$project_db" || -z "$knowledge_id" || -z "$key" ]]; then
  usage
  exit 2
fi
if [[ ! -f "$input" ]]; then
  echo "missing plaintext input: $input" >&2
  exit 2
fi

mkdir -p "$(dirname "$output")" "$(dirname "$meta")"

plain_sha="$(shasum -a 256 "$input" | awk '{print $1}')"
plain_size="$(wc -c "$input" | awk '{print $1}')"
created_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
token_ref_hash="$(python3 - "$input" <<'PY'
import hashlib, json, sys
obj = json.load(open(sys.argv[1], encoding='utf-8'))
token_id = obj.get("token", {}).get("id", "")
print(hashlib.sha256(token_id.encode("utf-8")).hexdigest() if token_id else "")
PY
)"

openssl enc -aes-256-cbc -pbkdf2 -salt \
  -in "$input" \
  -out "$output" \
  -pass "pass:$key"

cipher_sha="$(shasum -a 256 "$output" | awk '{print $1}')"
cipher_size="$(wc -c "$output" | awk '{print $1}')"

python3 - "$meta" "$input" "$output" "$plain_sha" "$plain_size" "$cipher_sha" "$cipher_size" "$created_at" "$token_ref_hash" <<'PY'
import json
import sys
from pathlib import Path

meta_path = Path(sys.argv[1])
payload = {
    "schemaVersion": "1.0",
    "artifactType": "lead_developer_authenticity_token_encrypted",
    "sourceArtifact": sys.argv[2],
    "encryptedArtifact": sys.argv[3],
    "encryption": {
        "cipher": "aes-256-cbc",
        "kdf": "pbkdf2",
        "salt": "openssl-managed",
    },
    "integrity": {
        "plaintextSha256": sys.argv[4],
        "plaintextSizeBytes": int(sys.argv[5]),
        "ciphertextSha256": sys.argv[6],
        "ciphertextSizeBytes": int(sys.argv[7]),
    },
    "gatePolicy": {
        "gate1": "valid_decryption_key_required",
        "gate2": "approved_governance_event_required_for_restore",
        "governancePhase": "core_secret_restore",
    },
    "tokenRef": {
        "hash": ("sha256:" + sys.argv[9]) if sys.argv[9] else "",
    },
    "createdAtUtc": sys.argv[8],
}
meta_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY

python3 - "$index" "$output" "$meta" "$token_ref_hash" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
for item in data.get("artifacts", []):
    item.pop("tokenId", None)
    item["tokenRefHash"] = ("sha256:" + sys.argv[4]) if sys.argv[4] else ""
    item["artifact"] = sys.argv[2]
    item["metadata"] = sys.argv[3]
    item["encryption"] = "aes-256-cbc+pbkdf2"
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY

sqlite3 "$project_db" <<SQL
update knowledge_evidence
set artifact_path = '$(printf "%s" "$output" | sed "s/'/''/g")',
    artifact_sha256 = '$cipher_sha',
    artifact_size_bytes = $cipher_size,
    evidence_type = 'authenticity_token_encrypted',
    notes = 'Encrypted authenticity payload. Restore requires key + approved governance event.'
where knowledge_id = '$(printf "%s" "$knowledge_id" | sed "s/'/''/g")';

update knowledge_entries
set context_snapshot = '{"tokenRefHash":"sha256:${token_ref_hash}","artifact":"$(printf "%s" "$output" | sed "s/'/''/g")","index":"policy/authenticity/index.json"}'
where knowledge_id = '$(printf "%s" "$knowledge_id" | sed "s/'/''/g")';
SQL

rm -f "$input"

echo "protected:$output"
