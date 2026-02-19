#!/usr/bin/env python3
import argparse
import csv
import datetime as dt
import fnmatch
import json
import subprocess
from pathlib import Path
from typing import Dict, List, Set


def changed_paths() -> List[str]:
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"],
            check=True,
            capture_output=True,
            text=True,
        )
    except Exception:
        return []
    paths: List[str] = []
    for line in result.stdout.splitlines():
        if not line:
            continue
        # Porcelain path starts at col 4; rename entries use "old -> new".
        raw = line[3:].strip()
        if " -> " in raw:
            raw = raw.split(" -> ", 1)[1].strip()
        if raw:
            paths.append(raw.replace("\\", "/"))
    return paths


def parse_issues(path: Path) -> List[Dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as f:
        return [{k: (v or "").strip() for k, v in row.items()} for row in csv.DictReader(f)]


def change_covers_target(change_path: str, target_path: str) -> bool:
    c = change_path.rstrip("/")
    t = target_path.rstrip("/")
    if c == t:
        return True
    return t.startswith(c + "/") or c.startswith(t + "/")


def main() -> int:
    parser = argparse.ArgumentParser(description="Tickle issues log when referenced components are being changed.")
    parser.add_argument("--issues", required=True, help="Path to issues CSV")
    parser.add_argument("--output", required=True, help="Path to output JSON report")
    parser.add_argument("--enforce", action="store_true", help="Exit non-zero when issue-referenced components change but issues log itself was not modified")
    args = parser.parse_args()

    issues_path = Path(args.issues)
    output_path = Path(args.output)
    if not issues_path.exists():
        raise SystemExit(f"Missing issues log: {issues_path}")

    issues = parse_issues(issues_path)
    changed = changed_paths()
    changed_set: Set[str] = set(changed)
    issues_path_posix = issues_path.as_posix()
    issues_changed = any(change_covers_target(ch, issues_path_posix) for ch in changed_set)

    triggered: List[Dict[str, object]] = []
    for issue in issues:
        comps = [c.strip() for c in issue.get("components", "").split("|") if c.strip()]
        if not comps:
            continue
        matched: List[str] = []
        for path in changed:
            for pattern in comps:
                if fnmatch.fnmatch(path, pattern) or change_covers_target(path, pattern):
                    matched.append(path)
                    break
        if matched:
            triggered.append({
                "id": issue.get("id", ""),
                "status": issue.get("status", ""),
                "title": issue.get("title", ""),
                "matchedComponents": sorted(set(matched)),
            })

    requires_update = bool(triggered) and (not issues_changed)
    payload = {
        "schemaVersion": "1",
        "generatedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "issuesLogPath": issues_path.as_posix(),
        "issuesLogTouchedInWorkingTree": issues_changed,
        "changedPathCount": len(changed),
        "triggeredIssueCount": len(triggered),
        "requiresIssuesLogUpdate": requires_update,
        "triggeredIssues": triggered,
        "message": (
            "Issue-referenced components are being modified; update docs/issues-log.csv in the same change."
            if requires_update
            else "No issue-log tickle required."
        ),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")

    if args.enforce and requires_update:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
