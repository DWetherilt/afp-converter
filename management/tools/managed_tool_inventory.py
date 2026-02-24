#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def norm(path: Path) -> str:
    return str(path).replace("\\", "/")


def infer_realm(path: str) -> str:
    p = path.lower()
    if p.startswith("management/pm-") or p.startswith("management/pm/") or p.startswith("management/tools/"):
        if "fake-afp-engine" in p:
            return "application"
        return "pm"
    if p.startswith("product/") or p.startswith("afp-"):
        return "application"
    if p.startswith("product/boilerplate/"):
        return "boilerplate"
    return "pm"


def command_for(path: str) -> str:
    if path.endswith(".py"):
        return f"python3 {path}"
    if path.endswith(".sh"):
        return f"bash {path}"
    if path.endswith(".java") and path.endswith("StateDatabaseTool.java"):
        return "java -cp <pm-tools-runtime-cp> solutions.pointzero.symphony.pm.tools.StateDatabaseTool <command> ..."
    if path.endswith(".java") and path.endswith("PmConsoleMain.java"):
        return "pmconsole <subcommand>"
    return path


def entrypoint_type(path: str) -> str:
    if path.endswith(".py") or path.endswith(".sh"):
        return "script"
    if path.endswith(".java"):
        return "java_main"
    return "file"


def collect_tools(root: Path) -> list[dict]:
    entries: list[dict] = []
    include_ext = {".py", ".sh", ".java"}
    skip_dirs = {
        ".git",
        ".gradle",
        ".idea",
        "build",
        "__pycache__",
        "product/afp-converter/preview/ci-artifacts/latest",
    }
    for base in [root / "tools", root / "management/pm-management/tools/src/main/java", root / "management/pm-console/src/main/java"]:
        if not base.exists():
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dnorm = norm(Path(dirpath).relative_to(root))
            dirnames[:] = [d for d in dirnames if all(not (Path(dnorm) / d).as_posix().startswith(sd) for sd in skip_dirs)]
            for fn in filenames:
                p = Path(dirpath) / fn
                rel = norm(p.relative_to(root))
                if p.suffix not in include_ext:
                    continue
                if "/tests/" in rel or rel.endswith("Test.java"):
                    continue
                if rel.endswith("WorkbookUpdater.bas"):
                    continue
                realm = infer_realm(rel)
                entries.append(
                    {
                        "toolId": rel.replace("/", "::"),
                        "path": rel,
                        "realm": realm,
                        "entrypointType": entrypoint_type(rel),
                        "entrypoint": command_for(rel),
                        "owner": f"{realm}-realm",
                    }
                )
    entries.sort(key=lambda e: (e["realm"], e["path"]))
    return entries


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate managed tooling inventory with realm ownership and entrypoints.")
    ap.add_argument("--root", default=".")
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    root = Path(args.root).resolve()
    tools = collect_tools(root)
    by_realm: dict[str, int] = {}
    for item in tools:
        by_realm[item["realm"]] = by_realm.get(item["realm"], 0) + 1

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": "filesystem-scan",
        "toolCount": len(tools),
        "byRealm": by_realm,
        "tools": tools,
    }
    out = Path(args.output)
    ensure_parent(out)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
