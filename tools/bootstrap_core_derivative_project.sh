#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <core-path> <project-id>" >&2
  exit 2
fi

core_path="$1"
project_id="$2"

if [[ ! -d "$core_path" ]]; then
  echo "missing core path: $core_path" >&2
  exit 2
fi

if [[ ! -f "$core_path/AI-POLICY.md" ]]; then
  echo "core path missing AI-POLICY.md: $core_path" >&2
  exit 2
fi

target_dir="derivatives/$project_id"
mkdir -p "$target_dir"
mkdir -p "$target_dir/core"
mkdir -p "$target_dir/contracts"

cp "$core_path/AI-POLICY.md" "$target_dir/core/AI-POLICY.md"

cat > "$target_dir/contracts/derivative-topology.json" <<EOF
{
  "schemaVersion": "1",
  "coreId": "ai-core-3",
  "projectId": "$project_id",
  "internalRealms": ["application", "pm", "boilerplate"],
  "linkContracts": {
    "coreDecisionRefField": "core_decision_ref",
    "coreActionRefField": "core_action_ref",
    "corePivotRefField": "core_pivot_ref"
  }
}
EOF

echo "bootstrapped:$target_dir"
