#!/usr/bin/env python3
import argparse
import json
import subprocess
from pathlib import Path


def load_manifest(path: Path) -> dict:
    if not path.exists():
        raise SystemExit(f"Missing workflow manifest: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise SystemExit(f"Invalid workflow manifest JSON: {exc}")
    if not isinstance(payload, dict):
        raise SystemExit("Workflow manifest root must be an object")
    return payload


def phase_index(manifest: dict) -> dict:
    out: dict[str, dict] = {}
    phases = manifest.get("phases")
    if isinstance(phases, list):
        for item in phases:
            if isinstance(item, dict):
                pid = str(item.get("id", "")).strip()
                if pid:
                    out[pid] = item
    return out


def adapter_phase_tasks(manifest: dict, adapter: str, phase: str) -> list[str]:
    adapters = manifest.get("adapters")
    if not isinstance(adapters, dict):
        return []
    adapter_obj = adapters.get(adapter)
    if not isinstance(adapter_obj, dict):
        return []
    ptm = adapter_obj.get("phaseTaskMap")
    if not isinstance(ptm, dict):
        return []
    tasks = ptm.get(phase)
    if not isinstance(tasks, list):
        return []
    return [str(x).strip() for x in tasks if str(x).strip()]


def adapter_command(manifest: dict, adapter: str) -> list[str]:
    adapters = manifest.get("adapters")
    if not isinstance(adapters, dict):
        return []
    adapter_obj = adapters.get(adapter)
    if not isinstance(adapter_obj, dict):
        return []
    cmd = adapter_obj.get("command")
    if not isinstance(cmd, list):
        return []
    return [str(x).strip() for x in cmd if str(x).strip()]


def cmd_list(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    pidx = phase_index(manifest)
    defaults = manifest.get("defaults") if isinstance(manifest.get("defaults"), dict) else {}
    print(f"Workflow: {manifest.get('name', '<unnamed>')}")
    print(f"Manifest: {args.manifest}")
    print("Phases:")
    for pid, item in sorted(pidx.items()):
        title = str(item.get("title", "")).strip() or pid
        print(f"  - {pid}: {title}")
        tasks = adapter_phase_tasks(manifest, args.adapter, pid)
        if tasks:
            print(f"    {args.adapter} tasks: {', '.join(tasks)}")
    if defaults:
        print("Defaults:")
        for key in ("executeApplicationPhase", "pmRefreshPhase", "gradleDefaultPhase"):
            val = str(defaults.get(key, "")).strip()
            if val:
                print(f"  - {key}: {val}")
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    manifest = load_manifest(Path(args.manifest))
    pidx = phase_index(manifest)

    phase = args.phase.strip()
    if not phase:
        defaults = manifest.get("defaults") if isinstance(manifest.get("defaults"), dict) else {}
        phase = str(defaults.get("gradleDefaultPhase", "")).strip()
    if not phase:
        raise SystemExit("Missing phase and no default phase available")
    if phase not in pidx:
        raise SystemExit(f"Unknown phase: {phase}")

    tasks = adapter_phase_tasks(manifest, args.adapter, phase)
    if not tasks:
        raise SystemExit(f"No task mapping for adapter='{args.adapter}' phase='{phase}'")
    base_cmd = adapter_command(manifest, args.adapter)
    if not base_cmd:
        raise SystemExit(f"No command mapping for adapter='{args.adapter}'")

    cmd = [*base_cmd, *tasks]
    print("Running:", " ".join(cmd))
    if args.dry_run:
        return 0
    proc = subprocess.run(cmd)
    return proc.returncode


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Environment-agnostic PM workflow runner")
    parser.add_argument(
        "--manifest",
        default="management/pm/workflow/workflow-manifest.json",
        help="Workflow manifest path"
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_list = sub.add_parser("list", help="List phases and adapter mappings")
    p_list.add_argument("--adapter", default="gradle", help="Adapter key (default: gradle)")
    p_list.set_defaults(func=cmd_list)

    p_run = sub.add_parser("run", help="Run a phase with adapter mapping")
    p_run.add_argument("--adapter", default="gradle", help="Adapter key (default: gradle)")
    p_run.add_argument("--phase", default="", help="Phase id (uses manifest default when empty)")
    p_run.add_argument("--dry-run", action="store_true", help="Print command only")
    p_run.set_defaults(func=cmd_run)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
