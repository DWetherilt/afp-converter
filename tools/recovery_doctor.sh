#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

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

echo "[1/6] Restore binary artifacts from policy"
tools/restore_binary_artifacts.sh

echo "[2/6] Re-materialize filesystem from SQL"
tools/manifest_from_sql.sh

echo "[3/6] Verify realm drift"
tools/realm_drift_verify.sh

echo "[4/6] Verify minimal snapshot keep-set"
tools/minimal_snapshot_guard.sh

echo "[5/7] Checkpoint PM core reports before mutation"
checkpoint_reports

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
tools/check_critical_db_hashes.sh baseline
tools/check_critical_db_hashes.sh check

echo "recovery_doctor=PASS"
