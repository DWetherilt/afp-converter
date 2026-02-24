#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "[1/6] Restore binary artifacts (strict)"
STRICT_MODE=true management/tools/restore_binary_artifacts.sh

echo "[2/6] Re-materialize filesystem from SQL"
management/tools/manifest_from_sql.sh

echo "[3/6] Verify realm drift"
management/tools/realm_drift_verify.sh

echo "[4/6] Verify minimal snapshot keep-set"
management/tools/minimal_snapshot_guard.sh

echo "[5/6] Emit SQL drift summary"
python3 management/tools/sql_drift_summary.py --output management/pm/reports/sql-drift-summary.json --enforce

echo "[6/6] Verify critical DB hashes"
management/tools/check_critical_db_hashes.sh check

echo "recovery_quick_check=PASS"
