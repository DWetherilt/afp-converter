import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools' / 'environment_health_check.py'


class EnvironmentHealthCheckTests(unittest.TestCase):
    def test_ci_mode_passes_without_visible_console(self):
        with tempfile.TemporaryDirectory() as td:
            td_path = Path(td)
            pid_file = td_path / 'pid'
            log_file = td_path / 'log'
            out_file = td_path / 'health.json'
            pid_file.write_text(str(os.getpid()), encoding='utf-8')
            log_file.write_text('', encoding='utf-8')
            proc = subprocess.run(
                [
                    'python3', str(SCRIPT),
                    '--pid-file', str(pid_file),
                    '--log-file', str(log_file),
                    '--output', str(out_file),
                    '--expect-running', 'true',
                    '--expect-visible', 'false',
                    '--governance-mode', 'strict',
                    '--auto-launch-if-missing', 'false',
                    '--ci', 'true'
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            payload = json.loads(out_file.read_text(encoding='utf-8'))
            self.assertEqual(payload['status'], 'PASS')
            self.assertIn('visibility', payload)
            self.assertIn('terminalTabId', payload['visibility'])


if __name__ == '__main__':
    unittest.main()
