#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <target-repo-name> <package-name>"
  echo "Example: $0 pz-boilerplate-intelliJ 2026-02-18-some-change"
  exit 1
fi

target_repo="$1"
package_name="$2"
base_dir="boilerplate/update-packages/${target_repo}/${package_name}"
patch_dir="${base_dir}/patches"

mkdir -p "${patch_dir}"

if [[ ! -f "${base_dir}/README.md" ]]; then
  cat > "${base_dir}/README.md" <<'DOC'
# Update Package

## Purpose
Describe what this package applies.

## Contents
- patches/
- apply-checklist.md
- fresh-session-prompt.md
- package-manifest.json
DOC
fi

if [[ ! -f "${base_dir}/apply-checklist.md" ]]; then
  cat > "${base_dir}/apply-checklist.md" <<'DOC'
# Apply Checklist

1. Create rollback checkpoint in target repo.
2. Apply patch(es).
3. Run validations.
4. Commit.
DOC
fi

if [[ ! -f "${base_dir}/fresh-session-prompt.md" ]]; then
  cat > "${base_dir}/fresh-session-prompt.md" <<'DOC'
Apply this package in the target repository using apply-checklist.md.
DOC
fi

echo "Initialized update package at: ${base_dir}"
