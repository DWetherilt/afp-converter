#!/usr/bin/env python3
"""Sync realm governance policy rows from project-state into realm databases."""

from __future__ import annotations

import argparse
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_table(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        create table if not exists realm_policy_catalog (
          rule_id text primary key,
          realm text not null default '',
          category text not null default '',
          rule_text text not null default '',
          source_ref text not null default '',
          mutable_by text not null default 'human',
          enabled integer not null default 1,
          updated_at text not null
        )
        """
    )
    conn.execute(
        "create index if not exists idx_realm_policy_catalog_realm_category on realm_policy_catalog(realm, category)"
    )


def load_source_rules(project_db: Path) -> list[sqlite3.Row]:
    conn = sqlite3.connect(project_db)
    conn.row_factory = sqlite3.Row
    try:
        return list(
            conn.execute(
                """
                select rule_id, realm, category, rule_text, source_ref, mutable_by, enabled
                from policy_rule_catalog
                where enabled = 1
                order by realm asc, category asc, rule_id asc
                """
            )
        )
    finally:
        conn.close()


def realm_filter(target_realm: str, source_realm: str) -> bool:
    source = (source_realm or "").strip().lower()
    target = (target_realm or "").strip().lower()
    if not target:
        return False
    if source == target:
        return True
    if source in {"pm", ""}:
        return True
    return False


def sync_target(target_realm: str, db_path: Path, rows: Iterable[sqlite3.Row]) -> int:
    selected = [r for r in rows if realm_filter(target_realm, str(r["realm"]))]
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        ensure_table(conn)
        cur = conn.cursor()
        for row in selected:
            cur.execute(
                """
                insert or replace into realm_policy_catalog(
                  rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at
                ) values(?,?,?,?,?,?,?,?)
                """,
                (
                    str(row["rule_id"]),
                    str(row["realm"] or target_realm),
                    str(row["category"] or ""),
                    str(row["rule_text"] or ""),
                    str(row["source_ref"] or ""),
                    str(row["mutable_by"] or "human"),
                    int(row["enabled"] or 0),
                    now_iso(),
                ),
            )
        conn.commit()
        return len(selected)
    finally:
        conn.close()


def main() -> int:
    ap = argparse.ArgumentParser(description="Sync realm policy catalogs from project-state rules.")
    ap.add_argument("--project-db", required=True)
    ap.add_argument("--application-db", required=True)
    ap.add_argument("--pm-db", required=True)
    ap.add_argument("--boilerplate-db", required=True)
    ap.add_argument("--report", required=True)
    args = ap.parse_args()

    rows = load_source_rules(Path(args.project_db))
    counts = {
        "application": sync_target("application", Path(args.application_db), rows),
        "pm": sync_target("pm", Path(args.pm_db), rows),
        "boilerplate": sync_target("boilerplate", Path(args.boilerplate_db), rows),
    }
    report = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": str(args.project_db),
        "targets": [
            {"realm": "application", "db": args.application_db, "ruleCount": counts["application"]},
            {"realm": "pm", "db": args.pm_db, "ruleCount": counts["pm"]},
            {"realm": "boilerplate", "db": args.boilerplate_db, "ruleCount": counts["boilerplate"]},
        ],
    }
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
