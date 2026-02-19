#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def as_bool(raw: str) -> bool:
    return str(raw or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def main() -> int:
    ap = argparse.ArgumentParser(description="Warn/fail on unresolved knowledge->decision links per realm.")
    ap.add_argument("--sync-report", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--strict", default="false")
    args = ap.parse_args()

    payload = load_json(Path(args.sync_report))
    targets = payload.get("targets", []) if isinstance(payload, dict) else []
    rows = []
    unresolved_total = 0
    for row in targets:
        if not isinstance(row, dict):
            continue
        unresolved = int(row.get("unresolvedDecisionLinks", 0) or 0)
        resolved = int(row.get("resolvedDecisionLinks", 0) or 0)
        total = int(row.get("decisionLinkCount", 0) or 0)
        unresolved_total += unresolved
        rows.append(
            {
                "realm": str(row.get("realm", "")),
                "decisionLinkCount": total,
                "resolvedDecisionLinks": resolved,
                "unresolvedDecisionLinks": unresolved,
                "status": "FAIL" if unresolved > 0 else "PASS",
            }
        )

    strict = as_bool(args.strict)
    status = "PASS" if unresolved_total == 0 else ("FAIL" if strict else "WARN")
    out = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": args.sync_report,
        "strict": strict,
        "overallStatus": status,
        "unresolvedTotal": unresolved_total,
        "targets": rows,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    if status == "FAIL":
        print(f"knowledge_link_gate=FAIL unresolved={unresolved_total}")
        return 2
    if status == "WARN":
        print(f"knowledge_link_gate=WARN unresolved={unresolved_total}")
        return 0
    print("knowledge_link_gate=PASS unresolved=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
