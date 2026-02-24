#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


PATH_PATTERN = re.compile(r"""['"]((?:management/pm/state|management/pm/reports|docs|tools|preview|boilerplate|management|product)/[^'"]+)['"]""")
DB_PATH_PATTERN = re.compile(r"""['"]((?:management/pm/state|product/afp-converter/preview/ci-artifacts/latest)/[^'"]+\.sqlite)['"]""")
ENV_HINT_PATTERN = re.compile(r"""(?:\$\{?([A-Z_][A-Z0-9_]*)\}?)""")


def classify(value: str, context: str) -> tuple[str, str]:
    v = value.lower()
    if ".sqlite" in v:
        if "management/pm/state/project-state.sqlite" in v or "management/pm/state/boilerplate-state.sqlite" in v:
            return "db-hardcoded", "Direct database path literal found."
        return "db-path", "Database path literal found."
    if "management/pm/reports/" in v:
        return "output-path", "Report output path literal found."
    if "management/docs/" in v:
        return "input-path", "Document source path literal found."
    if context.startswith("env:"):
        return "env-derived", "Derived from environment variable."
    return "constant", "Constant argument."


def scan_file(path: Path, root: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8", errors="replace")
    rows: list[dict] = []
    rel = str(path.relative_to(root)).replace("\\", "/")
    for m in PATH_PATTERN.finditer(text):
        value = m.group(1)
        line = text.count("\n", 0, m.start()) + 1
        kind, reason = classify(value, "literal")
        rows.append(
            {
                "sourceFile": rel,
                "line": line,
                "argumentValue": value,
                "classification": kind,
                "reason": reason,
            }
        )
    for m in DB_PATH_PATTERN.finditer(text):
        value = m.group(1)
        line = text.count("\n", 0, m.start()) + 1
        rows.append(
            {
                "sourceFile": rel,
                "line": line,
                "argumentValue": value,
                "classification": "db-hardcoded",
                "reason": "Database path literal found.",
            }
        )
    for m in ENV_HINT_PATTERN.finditer(text):
        env = m.group(1)
        if not env:
            continue
        line = text.count("\n", 0, m.start()) + 1
        rows.append(
            {
                "sourceFile": rel,
                "line": line,
                "argumentValue": env,
                "classification": "env-derived",
                "reason": "Argument appears derived from environment variable.",
            }
        )
    return rows


def dedupe(rows: list[dict]) -> list[dict]:
    seen = set()
    out = []
    for r in rows:
        key = (r["sourceFile"], r["line"], r["argumentValue"], r["classification"])
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    out.sort(key=lambda x: (x["classification"], x["sourceFile"], x["line"], x["argumentValue"]))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="Build command argument source compliance matrix for managed tooling.")
    ap.add_argument("--root", default=".")
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    root = Path(args.root).resolve()
    targets = [root / "build.gradle"] + sorted((root / "tools").glob("*.py")) + sorted((root / "tools").glob("*.sh"))
    rows: list[dict] = []
    for p in targets:
        if p.exists():
            rows.extend(scan_file(p, root))
    rows = dedupe(rows)

    by_class: dict[str, int] = {}
    for r in rows:
        by_class[r["classification"]] = by_class.get(r["classification"], 0) + 1

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": "static-argument-scan",
        "rowCount": len(rows),
        "byClassification": by_class,
        "rows": rows,
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
