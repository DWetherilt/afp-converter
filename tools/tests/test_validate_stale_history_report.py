import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools' / 'validate_stale_history_report.py'


class ValidateStaleHistoryReportTests(unittest.TestCase):
    def test_valid_history_passes(self):
        with tempfile.TemporaryDirectory() as td:
            history = Path(td) / 'history.json'
            history.write_text(json.dumps({
                'schemaVersion': '1',
                'events': [
                    {'generatedAt': '2026-02-19T00:00:00Z', 'staleCandidateCount': 0, 'status': 'PASS'}
                ]
            }), encoding='utf-8')
            proc = subprocess.run(
                ['python3', str(SCRIPT), '--input', str(history)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)


if __name__ == '__main__':
    unittest.main()
