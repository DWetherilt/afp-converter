from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


class PresentationRefLintTest(unittest.TestCase):
    def run_lint(self, root: Path) -> subprocess.CompletedProcess:
        return subprocess.run([
            "python3", "management/tools/presentation_ref_lint.py",
            "--actions", str(root / "actions.json"),
            "--decisions", str(root / "decisions.json"),
            "--knowledge", str(root / "knowledge.json"),
            "--governance", str(root / "governance.json"),
            "--version", str(root / "version.json"),
            "--output", str(root / "lint.json"),
        ], check=False, capture_output=True, text=True)

    def test_passes_when_refs_present(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "actions.json").write_text(json.dumps({"actions": [{"actionId": "A-1", "actionRef": "Action | 2026-02-19 | #001"}]}), encoding="utf-8")
            (root / "decisions.json").write_text(json.dumps({"decisions": [{"decisionId": "D-1", "decisionRef": "Decision | 2026-02-19 | #001"}]}), encoding="utf-8")
            (root / "knowledge.json").write_text(json.dumps({"knowledge": [{"knowledgeId": "K-1", "knowledgeRef": "Knowledge | 2026-02-19 | #001"}]}), encoding="utf-8")
            (root / "governance.json").write_text(json.dumps({"activeBreaches": [{"event_id": "G-1", "event_ref": "Governance Event | 2026-02-19 | #001"}], "trustEvents": [], "policyGovernanceEvents": []}), encoding="utf-8")
            (root / "version.json").write_text(json.dumps({"releaseName": "Release 1", "releaseRef": "Release | 2026-02-19 | #001"}), encoding="utf-8")

            result = self.run_lint(root)
            self.assertEqual(0, result.returncode)
            payload = json.loads((root / "lint.json").read_text(encoding="utf-8"))
            self.assertTrue(payload["ok"])

    def test_fails_when_missing_or_invalid_refs(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "actions.json").write_text(json.dumps({"actions": [{"actionId": "A-1"}]}), encoding="utf-8")
            (root / "decisions.json").write_text(json.dumps({"decisions": [{"decisionId": "D-1", "decisionRef": "bad"}]}), encoding="utf-8")
            (root / "knowledge.json").write_text(json.dumps({"knowledge": [{"knowledgeId": "K-1", "knowledgeRef": "Knowledge | 2026-02-19 | #001"}]}), encoding="utf-8")
            (root / "governance.json").write_text(json.dumps({"activeBreaches": [{"event_id": "G-1"}], "trustEvents": [], "policyGovernanceEvents": []}), encoding="utf-8")
            (root / "version.json").write_text(json.dumps({"releaseName": "", "releaseRef": "x"}), encoding="utf-8")

            result = self.run_lint(root)
            self.assertEqual(2, result.returncode)
            payload = json.loads((root / "lint.json").read_text(encoding="utf-8"))
            self.assertFalse(payload["ok"])
            self.assertGreater(payload["violationCount"], 0)


if __name__ == "__main__":
    unittest.main()
