#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <realm: application|pm|boilerplate> <file1> [file2 ...]" >&2
  exit 2
fi

realm="$1"
shift

case "$realm" in
  application) db="management/pm/state/application-realm.sqlite" ;;
  pm) db="management/pm/state/pm-realm.sqlite" ;;
  boilerplate) db="management/pm/state/boilerplate-realm.sqlite" ;;
  *) echo "unsupported realm: $realm" >&2; exit 2 ;;
esac

for path in "$@"; do
  if [[ ! -f "$path" ]]; then
    echo "skip_missing:$path"
    continue
  fi
  sha=$(/usr/bin/shasum -a 256 "$path" | /usr/bin/awk '{print $1}')
  /usr/bin/sqlite3 "$db" \
    "insert or replace into code_file_content(path, realm, encoding, content, content_sha256, updated_at) values('$path','$realm','utf-8',cast(readfile('$path') as text),'$sha',datetime('now'));"
  echo "upserted:$realm:$path"
done
