#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def run_lines(*cmd: str) -> list[str]:
    proc = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if proc.returncode != 0:
        return []
    return [line.strip().replace("\\", "/") for line in proc.stdout.splitlines() if line.strip()]


def git_changed_paths() -> list[str]:
    changed = set(run_lines("git", "diff", "--name-only"))
    changed.update(run_lines("git", "diff", "--cached", "--name-only"))
    changed.update(run_lines("git", "ls-files", "--others", "--exclude-standard"))
    return sorted(changed)


def assign_slice(path: str) -> str:
    if path == "AI-POLICY.md" or path.startswith("policy/"):
        return "SLICE-POLICY"
    if path.startswith("product/"):
        return "SLICE-PRODUCT"
    return "SLICE-MANAGEMENT"


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate realm/ownership slice report and file manifests from git changes.")
    ap.add_argument("--output", required=True)
    ap.add_argument("--manifest-dir", required=True)
    args = ap.parse_args()

    out = Path(args.output)
    manifest_dir = Path(args.manifest_dir)
    ensure_parent(out)
    manifest_dir.mkdir(parents=True, exist_ok=True)

    changed = git_changed_paths()
    buckets: dict[str, list[str]] = {
        "SLICE-POLICY": [],
        "SLICE-MANAGEMENT": [],
        "SLICE-PRODUCT": [],
    }
    for p in changed:
        buckets[assign_slice(p)].append(p)

    manifest_paths: dict[str, str] = {}
    for slice_id, paths in buckets.items():
        mf = manifest_dir / f"{slice_id.lower()}-paths.txt"
        mf.write_text("\n".join(paths) + ("\n" if paths else ""), encoding="utf-8")
        manifest_paths[slice_id] = str(mf).replace("\\", "/")

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": "git working tree",
        "sliceOrder": ["SLICE-POLICY", "SLICE-MANAGEMENT", "SLICE-PRODUCT"],
        "slices": [
            {
                "sliceId": sid,
                "pathCount": len(buckets[sid]),
                "manifestPath": manifest_paths[sid],
                "gitAddCommand": f"git add --pathspec-from-file={manifest_paths[sid]}",
            }
            for sid in ["SLICE-POLICY", "SLICE-MANAGEMENT", "SLICE-PRODUCT"]
        ],
        "totalChangedPaths": len(changed),
    }
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
