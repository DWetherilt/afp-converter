#!/usr/bin/env python3
import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any


DEFAULT_BASELINE = [
    "VERSION",
    "build.gradle",
    "settings.gradle",
    "AI-POLICY.md",
    "README.md",
    "SESSION_CHANGELOG.md",
    "docs/project-plan.md",
    "docs/project-plan-progress.csv",
    "docs/project-plan-progress.xlsx",
    "docs/issues-log.csv",
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(8192)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def try_git_head(root: Path) -> str:
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        )
        return proc.stdout.strip()
    except Exception:
        return ""


def safe_id(raw: str) -> str:
    out = re.sub(r"[^A-Za-z0-9._-]+", "-", raw.strip())
    return out.strip("-")[:80] or "checkpoint"


def load_manifest_files(root: Path, manifest_path: Path) -> list[str]:
    if not manifest_path.exists():
        return []
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    out: list[str] = []
    for item in payload.get("files", []):
        if not isinstance(item, dict):
            continue
        rel = item.get("path")
        exists = bool(item.get("exists", False))
        if isinstance(rel, str) and exists:
            out.append(rel.replace("\\", "/"))
    return out


def collect_files(root: Path, manifest: Path) -> list[str]:
    files = set(DEFAULT_BASELINE)
    files.update(load_manifest_files(root, manifest))
    keep = []
    for rel in sorted(files):
        p = (root / rel).resolve()
        try:
            p.relative_to(root.resolve())
        except Exception:
            continue
        if p.exists() and p.is_file():
            keep.append(rel)
    return keep


def index_path(base: Path) -> Path:
    return base / "index.json"


def load_index(base: Path) -> dict[str, Any]:
    p = index_path(base)
    if not p.exists():
        return {"schemaVersion": "1", "checkpoints": []}
    return json.loads(p.read_text(encoding="utf-8"))


def save_index(base: Path, payload: dict[str, Any]) -> None:
    base.mkdir(parents=True, exist_ok=True)
    index_path(base).write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def cmd_create(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    base = (root / args.base).resolve()
    manifest = (root / args.manifest).resolve()

    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    version = (root / "VERSION").read_text(encoding="utf-8").strip() if (root / "VERSION").exists() else "unknown"
    label = safe_id(args.label or "")
    checkpoint_id = safe_id(args.id or f"{now.strftime('%Y%m%dT%H%M%SZ')}-{version}-{label}" if label else f"{now.strftime('%Y%m%dT%H%M%SZ')}-{version}")
    dest = base / checkpoint_id
    files_dir = dest / "files"

    if dest.exists():
        raise SystemExit(f"Checkpoint already exists: {dest}")

    rel_files = collect_files(root, manifest)
    file_entries = []
    for rel in rel_files:
        src = root / rel
        out = files_dir / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, out)
        file_entries.append(
            {
                "path": rel,
                "sizeBytes": src.stat().st_size,
                "sha256": sha256(src),
            }
        )

    meta = {
        "schemaVersion": "1",
        "id": checkpoint_id,
        "createdAt": now.isoformat(),
        "version": version,
        "label": args.label or "",
        "gitHead": try_git_head(root),
        "fileCount": len(file_entries),
        "files": file_entries,
    }
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "manifest.json").write_text(json.dumps(meta, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")

    idx = load_index(base)
    checkpoints = [c for c in idx.get("checkpoints", []) if isinstance(c, dict)]
    checkpoints.append(
        {
            "id": checkpoint_id,
            "createdAt": meta["createdAt"],
            "version": version,
            "label": args.label or "",
            "fileCount": len(file_entries),
            "gitHead": meta["gitHead"],
        }
    )
    idx["checkpoints"] = checkpoints
    save_index(base, idx)

    print(checkpoint_id)
    return 0


def cmd_list(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    base = (root / args.base).resolve()
    idx = load_index(base)
    print(json.dumps(idx, indent=2, ensure_ascii=True))
    return 0


def cmd_restore(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    base = (root / args.base).resolve()
    cp = base / args.id
    meta_path = cp / "manifest.json"
    if not meta_path.exists():
        raise SystemExit(f"Missing checkpoint manifest: {meta_path}")
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    files = meta.get("files", [])
    if not isinstance(files, list):
        raise SystemExit("Invalid checkpoint manifest format")

    planned = []
    for item in files:
        if not isinstance(item, dict):
            continue
        rel = item.get("path")
        if not isinstance(rel, str):
            continue
        src = cp / "files" / rel
        dst = root / rel
        planned.append((rel, src, dst))

    if not args.apply:
        print(json.dumps({"checkpointId": args.id, "plannedRestoreCount": len(planned), "apply": False}, indent=2))
        return 0

    for rel, src, dst in planned:
        if not src.exists():
            raise SystemExit(f"Checkpoint file missing for {rel}: {src}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    print(json.dumps({"checkpointId": args.id, "restoredCount": len(planned), "apply": True}, indent=2))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Create/list/restore project rollback checkpoints.")
    parser.add_argument("--root", default=".", help="Repository root")
    parser.add_argument("--base", default="preview/checkpoints", help="Checkpoint base directory")
    parser.add_argument(
        "--manifest",
        default="preview/documentation-manifest.json",
        help="Documentation manifest used to select checkpoint files",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    create = sub.add_parser("create", help="Create checkpoint")
    create.add_argument("--id", help="Explicit checkpoint id")
    create.add_argument("--label", help="Human label")

    sub.add_parser("list", help="List checkpoints")

    restore = sub.add_parser("restore", help="Restore checkpoint")
    restore.add_argument("--id", required=True, help="Checkpoint id")
    restore.add_argument("--apply", action="store_true", help="Apply restore (default is dry-run)")

    args = parser.parse_args()
    if args.cmd == "create":
        return cmd_create(args)
    if args.cmd == "list":
        return cmd_list(args)
    if args.cmd == "restore":
        return cmd_restore(args)
    raise SystemExit(f"Unsupported command: {args.cmd}")


if __name__ == "__main__":
    raise SystemExit(main())
