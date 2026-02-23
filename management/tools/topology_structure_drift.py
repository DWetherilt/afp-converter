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


def main() -> int:
    ap = argparse.ArgumentParser(description="Checks topology/structure drift against core-derivative contract.")
    ap.add_argument("--topology", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--strict", default="false")
    args = ap.parse_args()

    topo = json.loads(Path(args.topology).read_text(encoding="utf-8"))
    expected_paths = [
        "policy",
        "management",
        "product",
        "management/pm/workflow/core-derivative-topology.json",
        "management/pm/workflow/realm-managed-manifest.json",
        "management/pm/workflow/path-contract.json",
        "management/pm/state/project-state.sqlite",
        "management/pm/state/core-realm.sqlite",
    ]
    missing = [p for p in expected_paths if not Path(p).exists()]

    core_id = ((topo.get("core") or {}).get("id") or "").strip()
    project = topo.get("project") or {}
    core_realms = (topo.get("core") or {}).get("realms") or []
    project_realms = project.get("realms") or []
    products = project.get("products") or []

    semantic_violations = []
    if core_id != "pzAiCore":
        semantic_violations.append(f"core.id must be pzAiCore (found: {core_id or '<empty>'})")
    if set(core_realms) != {"policy", "management", "product"}:
        semantic_violations.append("core.realms must be exactly [policy, management, product]")
    if set(project_realms) != {"policy", "management", "product"}:
        semantic_violations.append("project.realms must be exactly [policy, management, product]")
    for prod in products:
        realms = set(prod.get("realms") or [])
        if realms != {"policy", "management", "product"}:
            semantic_violations.append(f"product '{prod.get('id', '<unknown>')}' must inherit [policy, management, product]")

    violations = [f"missing path: {m}" for m in missing] + semantic_violations
    strict = str(args.strict).strip().lower() in {"1", "true", "yes"}
    ok = len(violations) == 0

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": args.topology,
        "strict": strict,
        "ok": ok,
        "violationCount": len(violations),
        "violations": violations,
        "expectedPaths": expected_paths,
    }
    out = Path(args.output)
    ensure_parent(out)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    if strict and not ok:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
