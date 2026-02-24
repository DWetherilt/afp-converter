#!/usr/bin/env python3
from __future__ import annotations

import csv
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path("management/tools/plan_progress_commit_marker.py")


class PlanProgressCommitMarkerTests(unittest.TestCase):
    def test_autofix_appends_not_committed_marker(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            csv_path = root / "progress.csv"
            out = root / "lint.json"
            with csv_path.open("w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(
                    f,
                    fieldnames=[
                        "workstream_uid",
                        "workstream_label",
                        "workstream_priority_rank",
                        "workstream",
                        "task",
                        "priority",
                        "status",
                        "percent_complete",
                        "last_updated",
                        "notes",
                    ],
                )
                w.writeheader()
                w.writerow(
                    {
                        "workstream_uid": "WS-X",
                        "workstream_label": "Workstream X",
                        "workstream_priority_rank": "1",
                        "workstream": "Workstream X",
                        "task": "some task",
                        "priority": "High",
                        "status": "Complete",
                        "percent_complete": "100",
                        "last_updated": "2026-02-23",
                        "notes": "",
                    }
                )
            cmd = [
                "python3",
                str(SCRIPT),
                "--csv",
                str(csv_path),
                "--output",
                str(out),
                "--autofix-missing-not-committed",
                "true",
                "--enforce",
                "true",
            ]
            proc = subprocess.run(cmd, check=False, capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            payload = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(payload["fixedCount"], 1)
            text = csv_path.read_text(encoding="utf-8")
            self.assertIn("[github:not-committed]", text)


if __name__ == "__main__":
    unittest.main()
