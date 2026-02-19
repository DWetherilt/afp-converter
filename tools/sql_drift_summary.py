#!/usr/bin/env python3
import argparse
import hashlib
import json
import sqlite3
from pathlib import Path


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_realm(root: Path, db_path: Path, realm: str) -> dict:
    conn = sqlite3.connect(str(db_path))
    try:
        rows = conn.execute(
            "select path, content_sha256 from code_file_content where realm = ? order by path",
            (realm,),
        ).fetchall()
    finally:
        conn.close()

    missing = []
    mismatch = []
    for rel_path, expected in rows:
        out = root / rel_path
        if not out.exists():
            missing.append(rel_path)
            continue
        actual = file_sha256(out)
        if actual != expected:
            mismatch.append(rel_path)

    return {
        "realm": realm,
        "database": str(db_path.relative_to(root)),
        "total": len(rows),
        "missingCount": len(missing),
        "mismatchCount": len(mismatch),
        "missingPreview": missing[:25],
        "mismatchPreview": mismatch[:25],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate SQL drift summary across realms.")
    parser.add_argument("--output", required=True, help="Output JSON file path.")
    parser.add_argument("--enforce", action="store_true", help="Exit non-zero when drift exists.")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    realms = [
        ("application", root / "pm/state/application-realm.sqlite"),
        ("pm", root / "pm/state/pm-realm.sqlite"),
        ("boilerplate", root / "pm/state/boilerplate-realm.sqlite"),
    ]

    summaries = [verify_realm(root, db, realm) for realm, db in realms]
    failed = [s for s in summaries if s["missingCount"] > 0 or s["mismatchCount"] > 0]
    payload = {
        "schemaVersion": "1",
        "generatedAt": __import__("datetime").datetime.utcnow().isoformat() + "Z",
        "status": "PASS" if not failed else "FAIL",
        "realms": summaries,
    }

    out = root / args.output
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    if args.enforce and failed:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
