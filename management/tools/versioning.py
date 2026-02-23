#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path


SEMVER_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$")


def read_version(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f"Missing version file: {path}")
    return path.read_text(encoding="utf-8").strip()


def write_version(path: Path, value: str) -> None:
    path.write_text(value + "\n", encoding="utf-8")


def parse_semver(value: str) -> tuple[int, int, int, str]:
    m = SEMVER_RE.match(value.strip())
    if not m:
        raise SystemExit(f"Invalid semantic version: {value}")
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), (m.group(4) or "")


def bump(value: str, part: str, preid: str) -> str:
    major, minor, patch, prerelease = parse_semver(value)
    if part == "major":
        return f"{major + 1}.0.0"
    if part == "minor":
        return f"{major}.{minor + 1}.0"
    if part == "patch":
        return f"{major}.{minor}.{patch + 1}"
    if part != "prerelease":
        raise SystemExit(f"Unsupported bump part: {part}")

    if prerelease:
        prefix, sep, tail = prerelease.rpartition(".")
        if sep and tail.isdigit():
            return f"{major}.{minor}.{patch}-{prefix}.{int(tail) + 1}"
        return f"{major}.{minor}.{patch}-{prerelease}.1"
    return f"{major}.{minor}.{patch}-{preid}.1"


def main() -> int:
    parser = argparse.ArgumentParser(description="Project version manager backed by VERSION file.")
    parser.add_argument("--file", default="VERSION", help="Version file path")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("current", help="Print current version")
    sub.add_parser("validate", help="Validate current version")

    bump_parser = sub.add_parser("bump", help="Bump semantic version")
    bump_parser.add_argument("--part", required=True, choices=["major", "minor", "patch", "prerelease"])
    bump_parser.add_argument("--preid", default="beta", help="Prerelease identifier for prerelease bump")
    bump_parser.add_argument("--json-out", help="Optional JSON output path")

    set_parser = sub.add_parser("set", help="Set explicit semantic version")
    set_parser.add_argument("--value", required=True, help="Version string")

    args = parser.parse_args()
    version_file = Path(args.file)
    current = read_version(version_file)

    if args.cmd == "current":
        print(current)
        return 0
    if args.cmd == "validate":
        parse_semver(current)
        print(current)
        return 0
    if args.cmd == "set":
        parse_semver(args.value)
        write_version(version_file, args.value)
        print(args.value)
        return 0

    next_version = bump(current, args.part, args.preid)
    parse_semver(next_version)
    write_version(version_file, next_version)
    print(next_version)

    if args.json_out:
        out = Path(args.json_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        payload = {"previous": current, "next": next_version, "part": args.part}
        out.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
