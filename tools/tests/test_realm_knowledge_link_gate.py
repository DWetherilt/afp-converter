#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


class RealmKnowledgeLinkGateTests(unittest.TestCase):
    def test_cross_realm_decision_ids_are_not_hard_failures(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            sync = root / "sync.json"
            queue = root / "queue.json"
            out = root / "gate.json"
            sync.write_text(
                json.dumps(
                    {
                        "targets": [
                            {
                                "realm": "pm",
                                "decisionLinkCount": 1,
                                "resolvedDecisionLinks": 0,
                                "unresolvedDecisionLinks": 1,
                                "unresolvedDecisionIds": ["APP-RENDER-001"],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            queue.write_text(
                json.dumps({"decisions": [{"decisionId": "APP-RENDER-001"}]}),
                encoding="utf-8",
            )
            proc = subprocess.run(
                [
                    "python3",
                    "tools/realm_knowledge_link_gate.py",
                    "--sync-report",
                    str(sync),
                    "--decision-queue",
                    str(queue),
                    "--output",
                    str(out),
                    "--strict",
                    "true",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(proc.returncode, 0)
            payload = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(payload["overallStatus"], "PASS")
            self.assertEqual(payload["unresolvedTotal"], 0)
            self.assertEqual(payload["crossRealmResolvedTotal"], 1)


if __name__ == "__main__":
    unittest.main()
