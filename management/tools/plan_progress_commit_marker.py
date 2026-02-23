#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path


COMPLETED_STATUSES = {"complete", "completed", "done"}
COMMITTED_MARKER = "[github:committed]"
NOT_COMMITTED_MARKER = "[github:not-committed]"


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def is_completed(status: str) -> bool:
    return (status or "").strip().lower() in COMPLETED_STATUSES


def marker_state(notes: str) -> str:
    lower = (notes or "").lower()
    if COMMITTED_MARKER in lower:
        return "committed"
    if NOT_COMMITTED_MARKER in lower:
        return "not_committed"
    return "missing"


def append_not_committed(notes: str) -> str:
    base = (notes or "").strip()
    if not base:
        return NOT_COMMITTED_MARKER
    return f"{base} {NOT_COMMITTED_MARKER}"


def main() -> int:
    ap = argparse.ArgumentParser(description="Lint and optionally autofix GitHub commit markers for completed plan rows.")
    ap.add_argument("--csv", required=True, help="Path to management/docs/project-plan-progress.csv")
    ap.add_argument("--output", required=True, help="Path to lint report JSON")
    ap.add_argument("--enforce", default="false", help="Fail with non-zero status when missing markers remain (true/false)")
    ap.add_argument("--autofix-missing-not-committed", default="false", help="Append [github:not-committed] for completed rows missing markers")
    args = ap.parse_args()

    csv_path = Path(args.csv)
    out_path = Path(args.output)
    ensure_parent(out_path)

    if not csv_path.exists():
        raise SystemExit(f"missing csv: {csv_path}")

    enforce = args.enforce.strip().lower() in {"1", "true", "yes"}
    autofix = args.autofix_missing_not_committed.strip().lower() in {"1", "true", "yes"}

    with csv_path.open("r", encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
        fieldnames = list(rows[0].keys()) if rows else []
    if "status" not in fieldnames or "notes" not in fieldnames:
        raise SystemExit("csv missing required columns: status, notes")

    completed_count = 0
    committed_count = 0
    not_committed_count = 0
    missing_count = 0
    fixed_count = 0
    missing_rows: list[dict[str, str]] = []

    for idx, row in enumerate(rows, start=2):
        if not is_completed(row.get("status", "")):
            continue
        completed_count += 1
        state = marker_state(row.get("notes", ""))
        if state == "committed":
            committed_count += 1
            continue
        if state == "not_committed":
            not_committed_count += 1
            continue
        if autofix:
            row["notes"] = append_not_committed(row.get("notes", ""))
            fixed_count += 1
            not_committed_count += 1
            continue
        missing_count += 1
        missing_rows.append(
            {
                "line": str(idx),
                "workstream_uid": row.get("workstream_uid", ""),
                "task": row.get("task", ""),
                "status": row.get("status", ""),
            }
        )

    if autofix and fixed_count > 0:
        with csv_path.open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "csvPath": str(csv_path).replace("\\", "/"),
        "enforce": enforce,
        "autofixMissingNotCommitted": autofix,
        "completedCount": completed_count,
        "committedCount": committed_count,
        "notCommittedCount": not_committed_count,
        "missingCount": missing_count,
        "fixedCount": fixed_count,
        "missingRows": missing_rows,
        "status": "PASS" if missing_count == 0 else "FAIL",
        "rule": "Completed rows must include [github:committed] or [github:not-committed].",
    }
    out_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    if enforce and missing_count > 0:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
