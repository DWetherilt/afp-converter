import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools' / 'pm_console_cleanup.py'


class PmConsoleCleanupTests(unittest.TestCase):
    def test_cleanup_report_has_warning_fields(self):
        with tempfile.TemporaryDirectory() as td:
            td_path = Path(td)
            pid_file = td_path / 'pid'
            out_file = td_path / 'cleanup.json'
            history_file = td_path / 'history.json'
            pid_file.write_text(str(os.getpid()), encoding='utf-8')

            proc = subprocess.run(
                [
                    'python3', str(SCRIPT),
                    '--pid-file', str(pid_file),
                    '--output', str(out_file),
                    '--history', str(history_file),
                    '--warn-threshold', '1'
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            payload = json.loads(out_file.read_text(encoding='utf-8'))
            self.assertIn('warnThreshold', payload)
            self.assertIn('consecutiveStaleCycles', payload)
            self.assertIn('warning', payload)
            self.assertTrue(history_file.exists())


if __name__ == '__main__':
    unittest.main()
