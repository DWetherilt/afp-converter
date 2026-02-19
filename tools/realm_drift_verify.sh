#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

verify_realm() {
  local db_rel="$1"
  local realm="$2"
  local db_path="$ROOT_DIR/$db_rel"
  local total=0
  local missing=0
  local mismatch=0

  if [[ ! -f "$db_path" ]]; then
    echo "realm=$realm status=missing_db db=$db_rel"
    return 1
  fi

  while IFS='|' read -r rel_path expected; do
    [[ -n "$rel_path" ]] || continue
    total=$((total+1))
    local file_path="$ROOT_DIR/$rel_path"
    if [[ ! -f "$file_path" ]]; then
      echo "missing:$realm:$rel_path"
      missing=$((missing+1))
      continue
    fi
    local actual
    actual=$(/usr/bin/shasum -a 256 "$file_path" | /usr/bin/awk '{print $1}')
    if [[ "$actual" != "$expected" ]]; then
      echo "mismatch:$realm:$rel_path"
      mismatch=$((mismatch+1))
    fi
  done < <(/usr/bin/sqlite3 "$db_path" "select path || '|' || content_sha256 from code_file_content where realm='${realm}' order by path")

  echo "realm=$realm total=$total missing=$missing mismatch=$mismatch"
  [[ $missing -eq 0 && $mismatch -eq 0 ]]
}

ok=1
verify_realm "pm/state/application-realm.sqlite" "application" || ok=0
verify_realm "pm/state/pm-realm.sqlite" "pm" || ok=0
verify_realm "pm/state/boilerplate-realm.sqlite" "boilerplate" || ok=0

if [[ $ok -eq 1 ]]; then
  echo "drift_verify=PASS"
else
  echo "drift_verify=FAIL"
  exit 1
fi
