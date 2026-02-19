#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_DIR="$ROOT_DIR/boilerplate/update-packages/pz-boilerplate-intelliJ"

pkg_id="${1:-}"
if [[ -z "$pkg_id" ]]; then
  echo "usage: $0 <package-id>" >&2
  exit 2
fi

pkg_dir="$BASE_DIR/$pkg_id"
if [[ ! -d "$pkg_dir" ]]; then
  echo "missing package dir: $pkg_dir" >&2
  exit 1
fi

tools/validate_boilerplate_package.sh "$pkg_dir"

out_zip="$BASE_DIR/${pkg_id}.zip"
(
  cd "$BASE_DIR"
  rm -f "$out_zip"
  zip -rq "$out_zip" "$pkg_id"
)

sha=$(/usr/bin/shasum -a 256 "$out_zip" | /usr/bin/awk '{print $1}')
echo "$sha  $out_zip" > "${out_zip}.sha256"

echo "exported:$out_zip"
echo "checksum:${out_zip}.sha256"
