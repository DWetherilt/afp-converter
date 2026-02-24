#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path("management/tools/workstream_slice_report.py")


class WorkstreamSliceReportTests(unittest.TestCase):
    def test_generates_report_and_manifests(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "slice.json"
            manifests = Path(td) / "manifests"
            cmd = [
                "python3",
                str(SCRIPT),
                "--output",
                str(out),
                "--manifest-dir",
                str(manifests),
            ]
            proc = subprocess.run(cmd, check=False, capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            payload = json.loads(out.read_text(encoding="utf-8"))
            self.assertIn("slices", payload)
            self.assertEqual(len(payload["slices"]), 3)
            for entry in payload["slices"]:
                self.assertIn("sliceId", entry)
                self.assertIn("manifestPath", entry)
                self.assertTrue(Path(entry["manifestPath"]).exists())


if __name__ == "__main__":
    unittest.main()
