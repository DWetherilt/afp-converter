#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path


COMPLETED_STATUSES = {"complete", "completed", "done"}


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def letter_id(index: int) -> str:
    # 0 -> A, 25 -> Z, 26 -> AA
    n = index + 1
    out: list[str] = []
    while n > 0:
        n -= 1
        out.append(chr(ord("A") + (n % 26)))
        n //= 26
    return "".join(reversed(out))


def parse_rank(value: str) -> int:
    text = (value or "").strip()
    try:
        return int(text)
    except ValueError:
        return 9_999_999


def is_active(status: str) -> bool:
    return (status or "").strip().lower() not in COMPLETED_STATUSES


def main() -> int:
    ap = argparse.ArgumentParser(description="Build active workstream presentation index (A..Z) from project-plan-progress.csv.")
    ap.add_argument("--csv", required=True, help="Path to management/docs/project-plan-progress.csv")
    ap.add_argument("--output", required=True, help="Path to output JSON report")
    args = ap.parse_args()

    csv_path = Path(args.csv)
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    if not csv_path.exists():
        raise SystemExit(f"missing csv: {csv_path}")

    with csv_path.open("r", encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))

    grouped: dict[str, dict] = {}
    for row in rows:
        uid = (row.get("workstream_uid") or "").strip()
        if not uid:
            continue
        if not is_active(row.get("status", "")):
            continue
        entry = grouped.setdefault(
            uid,
            {
                "internalWorkstreamUid": uid,
                "internalWorkstreamLabel": (row.get("workstream_label") or row.get("workstream") or "").strip(),
                "priorityRank": parse_rank(row.get("workstream_priority_rank", "")),
                "taskCount": 0,
                "tasks": [],
            },
        )
        entry["taskCount"] += 1
        task = (row.get("task") or "").strip()
        if task:
            entry["tasks"].append(task)

    ordered = sorted(
        grouped.values(),
        key=lambda x: (x["priorityRank"], x["internalWorkstreamUid"]),
    )

    presentation = []
    for idx, item in enumerate(ordered):
        token = letter_id(idx)
        presentation.append(
            {
                "presentationWorkstream": f"Workstream {token}",
                "presentationToken": token,
                "internalWorkstreamUid": item["internalWorkstreamUid"],
                "internalWorkstreamLabel": item["internalWorkstreamLabel"],
                "priorityRank": item["priorityRank"],
                "taskCount": item["taskCount"],
                "tasks": item["tasks"],
            }
        )

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "sourceCsv": str(csv_path).replace("\\", "/"),
        "activeWorkstreamCount": len(presentation),
        "activeWorkstreams": presentation,
        "rules": {
            "internalIds": "Persistent unique IDs (workstream_uid) are authoritative for storage/history.",
            "presentationIds": "A..Z aliases are generated for active workstreams only and can change between cycles.",
            "activeDefinition": "Rows with status not in {complete, completed, done}.",
        },
    }
    out_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
