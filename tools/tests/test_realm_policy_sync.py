#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path


class RealmPolicySyncTests(unittest.TestCase):
    def test_sync_writes_realm_catalogs(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            project_db = root / "project.sqlite"
            app_db = root / "app.sqlite"
            pm_db = root / "pm.sqlite"
            boiler_db = root / "boiler.sqlite"
            report = root / "report.json"

            conn = sqlite3.connect(project_db)
            conn.execute(
                """
                create table policy_rule_catalog(
                  rule_id text primary key,
                  realm text not null default '',
                  category text not null default '',
                  rule_text text not null default '',
                  source_ref text not null default '',
                  mutable_by text not null default '',
                  enabled integer not null default 1,
                  updated_at text not null default ''
                )
                """
            )
            conn.execute(
                "insert into policy_rule_catalog(rule_id,realm,category,rule_text,source_ref,mutable_by,enabled,updated_at) values(?,?,?,?,?,?,?,datetime('now'))",
                ("PM-ONLY-001", "pm", "test", "pm rule", "test", "human", 1),
            )
            conn.execute(
                "insert into policy_rule_catalog(rule_id,realm,category,rule_text,source_ref,mutable_by,enabled,updated_at) values(?,?,?,?,?,?,?,datetime('now'))",
                ("APP-ONLY-001", "application", "test", "app rule", "test", "human", 1),
            )
            conn.commit()
            conn.close()

            cmd = [
                "python3",
                "tools/realm_policy_sync.py",
                "--project-db",
                str(project_db),
                "--application-db",
                str(app_db),
                "--pm-db",
                str(pm_db),
                "--boilerplate-db",
                str(boiler_db),
                "--report",
                str(report),
            ]
            subprocess.run(cmd, check=True)

            payload = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(payload["schemaVersion"], "1")
            self.assertEqual(len(payload["targets"]), 3)

            app_conn = sqlite3.connect(app_db)
            pm_conn = sqlite3.connect(pm_db)
            try:
                app_count = app_conn.execute("select count(*) from realm_policy_catalog").fetchone()[0]
                pm_count = pm_conn.execute("select count(*) from realm_policy_catalog").fetchone()[0]
            finally:
                app_conn.close()
                pm_conn.close()
            self.assertGreaterEqual(app_count, 1)
            self.assertGreaterEqual(pm_count, 1)


if __name__ == "__main__":
    unittest.main()
