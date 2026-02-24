#!/usr/bin/env python3
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REALMS = [
    (ROOT / 'management/pm/state/application-realm.sqlite', 'application'),
    (ROOT / 'management/pm/state/pm-realm.sqlite', 'pm'),
    (ROOT / 'management/pm/state/boilerplate-realm.sqlite', 'boilerplate'),
]


def materialize(db_path: Path, realm: str) -> int:
    if not db_path.exists():
        print(f"skip: missing database {db_path.relative_to(ROOT)}")
        return 0
    written = 0
    conn = sqlite3.connect(str(db_path))
    try:
        rows = conn.execute(
            "select path, content from code_file_content where realm = ? order by path",
            (realm,),
        ).fetchall()
        for rel_path, content in rows:
            if not rel_path:
                continue
            out_path = ROOT / rel_path
            out_path.parent.mkdir(parents=True, exist_ok=True)
            text = content if content is not None else ""
            with out_path.open("w", encoding="utf-8", newline="") as fh:
                fh.write(text)
            if text.startswith("#!"):
                out_path.chmod(out_path.stat().st_mode | 0o111)
            written += 1
    finally:
        conn.close()
    print(f"materialized:{realm}:{written}")
    return written


def main() -> int:
    total = 0
    for db, realm in REALMS:
        total += materialize(db, realm)

    gradlew = ROOT / 'gradlew'
    if gradlew.exists():
        gradlew.chmod(gradlew.stat().st_mode | 0o111)

    print(f"materialization_complete:{total}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
