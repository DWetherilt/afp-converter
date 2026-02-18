#!/usr/bin/env python3
import argparse
import csv
import datetime as dt
import json
from pathlib import Path
from typing import Dict, List


REQUIRED = [
    "workstream",
    "task",
    "priority",
    "status",
    "percent_complete",
    "last_updated",
    "notes",
]


def load_rows(path: Path) -> List[Dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        missing = [k for k in REQUIRED if k not in (reader.fieldnames or [])]
        if missing:
            raise SystemExit(f"CSV is missing required columns: {', '.join(missing)}")
        out: List[Dict[str, str]] = []
        for row in reader:
            out.append({k: (row.get(k) or "").strip() for k in REQUIRED})
        return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Export project plan progress CSV to JSON for Excel-native automation.")
    parser.add_argument("--csv", required=True, help="Source CSV path")
    parser.add_argument("--json", required=True, help="Destination JSON path")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    json_path = Path(args.json)
    if not csv_path.exists():
        raise SystemExit(f"Missing CSV: {csv_path}")

    rows = load_rows(csv_path)
    payload = {
        "schemaVersion": "1",
        "generatedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "sourceCsv": str(csv_path.as_posix()),
        "taskCount": len(rows),
        "tasks": rows,
        "followUps": [
            {
                "id": "restored-workbook-content-review",
                "status": "pending",
                "note": "Circle back and review restored workbook content against JSON/CSV sources."
            }
        ],
    }

    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
