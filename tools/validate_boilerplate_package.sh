#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_DIR="$ROOT_DIR/boilerplate/update-packages/pz-boilerplate-intelliJ"

pkg="${1:-}"
if [[ -z "$pkg" ]]; then
  echo "usage: $0 <package-id-or-path>" >&2
  exit 2
fi

if [[ -d "$pkg" ]]; then
  pkg_dir="$pkg"
else
  pkg_dir="$BASE_DIR/$pkg"
fi

if [[ ! -d "$pkg_dir" ]]; then
  echo "missing package dir: $pkg_dir" >&2
  exit 1
fi

required=(
  "README.md"
  "apply-checklist.md"
  "fresh-session-prompt.md"
  "package-manifest.json"
)

for f in "${required[@]}"; do
  if [[ ! -f "$pkg_dir/$f" ]]; then
    echo "missing:$pkg_dir/$f"
    exit 1
  fi
done

python3 - "$pkg_dir/package-manifest.json" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, 'r', encoding='utf-8') as fh:
    data = json.load(fh)
required = ["schemaVersion", "packageId", "sourceRepo", "targetRepo", "createdAt", "summary", "files", "validation"]
missing = [k for k in required if k not in data]
if missing:
    print("manifest_missing_keys:" + ",".join(missing))
    raise SystemExit(1)
if not isinstance(data["files"], list) or not data["files"]:
    print("manifest_invalid_files")
    raise SystemExit(1)
if not isinstance(data["validation"], list) or not data["validation"]:
    print("manifest_invalid_validation")
    raise SystemExit(1)
print("manifest_ok")
PY

echo "boilerplate_package_validation=PASS:$pkg_dir"
