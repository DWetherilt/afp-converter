#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def load_rows(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    rows = data.get("rows")
    return rows if isinstance(rows, list) else []


def main() -> int:
    ap = argparse.ArgumentParser(description="Fails on hardcoded critical database paths in managed tooling.")
    ap.add_argument("--compliance", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--strict", default="false")
    ap.add_argument("--gate-scope", default="build.gradle", help="Comma-separated source file prefixes to enforce strictly.")
    args = ap.parse_args()

    critical = {
        "management/pm/state/project-state.sqlite",
        "management/pm/state/boilerplate-state.sqlite",
        "management/pm/state/application-realm.sqlite",
        "management/pm/state/pm-realm.sqlite",
        "management/pm/state/boilerplate-realm.sqlite",
        "management/pm/state/core-realm.sqlite",
    }
    scope_prefixes = [s.strip() for s in str(args.gate_scope).split(",") if s.strip()]
    rows = load_rows(Path(args.compliance))
    violations = []
    for r in rows:
        if r.get("classification") not in {"db-hardcoded", "db-path"}:
            continue
        source_file = str(r.get("sourceFile", "")).strip()
        if scope_prefixes and not any(source_file.startswith(prefix) for prefix in scope_prefixes):
            continue
        val = str(r.get("argumentValue", "")).strip()
        if val in critical:
            violations.append(
                {
                    "sourceFile": source_file,
                    "line": r.get("line", 0),
                    "argumentValue": val,
                    "classification": r.get("classification", ""),
                }
            )

    strict = str(args.strict).strip().lower() in {"1", "true", "yes"}
    ok = len(violations) == 0
    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": args.compliance,
        "strict": strict,
        "ok": ok,
        "criticalPathCount": len(critical),
        "gateScope": scope_prefixes,
        "violationCount": len(violations),
        "violations": sorted(violations, key=lambda x: (x["sourceFile"], int(x["line"]))),
    }
    out = Path(args.output)
    ensure_parent(out)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    if strict and not ok:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
