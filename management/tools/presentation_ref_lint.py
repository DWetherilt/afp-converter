#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

REF_RE = re.compile(r"^[A-Za-z][A-Za-z ]* \| \d{4}-\d{2}-\d{2} \| #[0-9]{3}$")


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def add_violation(violations: list[dict], path: str, message: str, sample: str = "") -> None:
    row = {"path": path, "message": message}
    if sample:
        row["sample"] = sample
    violations.append(row)


def validate_ref_rows(violations: list[dict], payload: dict, rows_key: str, id_key: str, ref_key: str, path: str) -> None:
    rows = payload.get(rows_key, [])
    if not isinstance(rows, list):
        add_violation(violations, path, f"'{rows_key}' must be a JSON array")
        return
    for i, row in enumerate(rows):
        if not isinstance(row, dict):
            add_violation(violations, path, f"row {i} in '{rows_key}' must be object")
            continue
        raw_id = str(row.get(id_key, "")).strip()
        raw_ref = str(row.get(ref_key, "")).strip()
        if not raw_id:
            add_violation(violations, path, f"row {i} missing '{id_key}'")
        if not raw_ref:
            add_violation(violations, path, f"row {i} missing '{ref_key}'")
        elif not REF_RE.match(raw_ref):
            add_violation(violations, path, f"row {i} has invalid '{ref_key}' format", raw_ref)


def validate_governance(violations: list[dict], payload: dict, path: str) -> None:
    for section in ("activeBreaches", "trustEvents", "policyGovernanceEvents"):
        rows = payload.get(section, [])
        if not isinstance(rows, list):
            add_violation(violations, path, f"'{section}' must be a JSON array")
            continue
        for i, row in enumerate(rows):
            if not isinstance(row, dict):
                add_violation(violations, path, f"row {i} in '{section}' must be object")
                continue
            event_id = str(row.get("event_id", "")).strip()
            event_ref = str(row.get("event_ref", "")).strip()
            if not event_id:
                add_violation(violations, path, f"row {i} in '{section}' missing 'event_id'")
            if not event_ref:
                add_violation(violations, path, f"row {i} in '{section}' missing 'event_ref'")
            elif not REF_RE.match(event_ref):
                add_violation(violations, path, f"row {i} in '{section}' has invalid 'event_ref' format", event_ref)


def main() -> int:
    ap = argparse.ArgumentParser(description="Validate presentation-facing human-readable references in PM report payloads.")
    ap.add_argument("--actions", required=True)
    ap.add_argument("--decisions", required=True)
    ap.add_argument("--knowledge", required=True)
    ap.add_argument("--governance", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    violations: list[dict] = []

    actions_path = Path(args.actions)
    decisions_path = Path(args.decisions)
    knowledge_path = Path(args.knowledge)
    governance_path = Path(args.governance)
    version_path = Path(args.version)

    actions = load_json(actions_path)
    decisions = load_json(decisions_path)
    knowledge = load_json(knowledge_path)
    governance = load_json(governance_path)
    version = load_json(version_path)

    validate_ref_rows(violations, actions, "actions", "actionId", "actionRef", args.actions)
    validate_ref_rows(violations, decisions, "decisions", "decisionId", "decisionRef", args.decisions)
    validate_ref_rows(violations, knowledge, "knowledge", "knowledgeId", "knowledgeRef", args.knowledge)
    validate_governance(violations, governance, args.governance)

    release_name = str(version.get("releaseName", "")).strip()
    release_ref = str(version.get("releaseRef", "")).strip()
    if not release_name:
        add_violation(violations, args.version, "missing 'releaseName'")
    if not release_ref:
        add_violation(violations, args.version, "missing 'releaseRef'")
    elif not REF_RE.match(release_ref):
        add_violation(violations, args.version, "invalid 'releaseRef' format", release_ref)

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "ok": len(violations) == 0,
        "violationCount": len(violations),
        "violations": violations,
        "inputs": {
            "actions": args.actions,
            "decisions": args.decisions,
            "knowledge": args.knowledge,
            "governance": args.governance,
            "version": args.version,
        },
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    if violations:
        print(f"presentation_ref_lint=FAIL violations={len(violations)}")
        return 2
    print("presentation_ref_lint=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
