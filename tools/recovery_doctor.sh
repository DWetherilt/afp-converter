#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RECOVERY_STRICT_CONDITIONAL_BINARIES="${RECOVERY_STRICT_CONDITIONAL_BINARIES:-false}"
RECOVERY_CHECKPOINT_RETENTION_COUNT="${RECOVERY_CHECKPOINT_RETENTION_COUNT:-20}"
RECOVERY_SKIP_BASELINE_REFRESH="${RECOVERY_SKIP_BASELINE_REFRESH:-false}"

upsert_pm_text() {
  local path="$1"
  local sha
  sha=$(/usr/bin/shasum -a 256 "$path" | /usr/bin/awk '{print $1}')
  /usr/bin/sqlite3 pm/state/pm-realm.sqlite \
    "insert or replace into code_file_content(path, realm, encoding, content, content_sha256, updated_at) values('$path','pm','utf-8',cast(readfile('$path') as text),'$sha',datetime('now'));"
}

checkpoint_reports() {
  local ts
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  local cp_dir="pm/checkpoints/recovery-doctor-local/${ts}"
  mkdir -p "$cp_dir"
  for f in \
    pm/reports/project-plan-progress-data.json \
    pm/reports/issues-log-tickle.json \
    pm/reports/issues-effectiveness.json
  do
    if [[ -f "$f" ]]; then
      cp "$f" "$cp_dir/"
    fi
  done
  echo "checkpoint_created:$cp_dir"
}

prune_checkpoints() {
  local base_dir="pm/checkpoints/recovery-doctor-local"
  local keep_count="$RECOVERY_CHECKPOINT_RETENTION_COUNT"
  if [[ ! -d "$base_dir" ]]; then
    return 0
  fi
  if ! [[ "$keep_count" =~ ^[0-9]+$ ]]; then
    echo "invalid RECOVERY_CHECKPOINT_RETENTION_COUNT=$keep_count" >&2
    return 1
  fi
  local entries
  entries="$(ls -1 "$base_dir" 2>/dev/null | sort || true)"
  local total
  total="$(printf '%s\n' "$entries" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [[ "$total" -le "$keep_count" ]]; then
    return 0
  fi
  local delete_count=$((total - keep_count))
  printf '%s\n' "$entries" | sed '/^$/d' | head -n "$delete_count" | while IFS= read -r entry; do
    rm -rf "$base_dir/$entry"
    echo "checkpoint_pruned:$base_dir/$entry"
  done
}

echo "[1/7] Restore binary artifacts from policy"
if [[ "$RECOVERY_STRICT_CONDITIONAL_BINARIES" == "true" || "$RECOVERY_STRICT_CONDITIONAL_BINARIES" == "1" ]]; then
  STRICT_MODE=true REQUIRE_CONDITIONAL=true tools/restore_binary_artifacts.sh
else
  tools/restore_binary_artifacts.sh
fi

echo "[2/7] Re-materialize filesystem from SQL"
tools/manifest_from_sql.sh

echo "[3/7] Verify realm drift"
tools/realm_drift_verify.sh

echo "[4/7] Verify minimal snapshot keep-set"
tools/minimal_snapshot_guard.sh

echo "[5/7] Checkpoint PM core reports before mutation"
checkpoint_reports
prune_checkpoints

echo "[6/7] Rebuild PM core reports"
python3 tools/export_project_plan_progress_json.py \
  --csv docs/project-plan-progress.csv \
  --json pm/reports/project-plan-progress-data.json
python3 tools/issues_log_tickle.py \
  --issues docs/issues-log.csv \
  --output pm/reports/issues-log-tickle.json
python3 tools/issues_effectiveness_report.py \
  --issues docs/issues-log.csv \
  --tickle pm/reports/issues-log-tickle.json \
  --progress docs/project-plan-progress.csv \
  --fidelity preview/fidelity-report.json \
  --output pm/reports/issues-effectiveness.json

upsert_pm_text pm/reports/project-plan-progress-data.json
upsert_pm_text pm/reports/issues-log-tickle.json
upsert_pm_text pm/reports/issues-effectiveness.json

python3 tools/sql_drift_summary.py --output pm/reports/sql-drift-summary.json --enforce
upsert_pm_text pm/reports/sql-drift-summary.json

echo "[7/7] Refresh and verify critical DB hashes"
if [[ "$RECOVERY_SKIP_BASELINE_REFRESH" == "true" || "$RECOVERY_SKIP_BASELINE_REFRESH" == "1" ]]; then
  tools/check_critical_db_hashes.sh check
else
  tools/check_critical_db_hashes.sh baseline
  tools/check_critical_db_hashes.sh check
fi

echo "recovery_doctor=PASS"
