#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


class RealmPolicyDiffTests(unittest.TestCase):
    def test_marks_changed_rules_as_warning(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            app = root / 'app.json'
            pm = root / 'pm.json'
            boiler = root / 'boiler.json'
            snap = root / 'snapshot.json'
            out = root / 'diff.json'

            base = {
                'schemaVersion': '1',
                'rules': [
                    {
                        'ruleId': 'R-1',
                        'realm': 'pm',
                        'category': 'test',
                        'ruleText': 'old',
                        'sourceRef': 'x',
                        'mutableBy': 'human',
                        'enabled': True,
                    }
                ],
            }
            app.write_text(json.dumps(base), encoding='utf-8')
            pm.write_text(json.dumps(base), encoding='utf-8')
            boiler.write_text(json.dumps(base), encoding='utf-8')

            old_snapshot = {
                'schemaVersion': '1',
                'realms': {
                    'application': {'rules': base['rules']},
                    'pm': {'rules': [{**base['rules'][0], 'ruleText': 'different'}]},
                    'boilerplate': {'rules': base['rules']},
                },
            }
            snap.write_text(json.dumps(old_snapshot), encoding='utf-8')

            cmd = [
                'python3',
                'tools/realm_policy_diff.py',
                '--application', str(app),
                '--pm', str(pm),
                '--boilerplate', str(boiler),
                '--snapshot', str(snap),
                '--output', str(out),
            ]
            subprocess.run(cmd, check=True)
            payload = json.loads(out.read_text(encoding='utf-8'))
            self.assertEqual(payload['realms']['pm']['severity'], 'warning')
            self.assertIn(payload['overallSeverity'], {'warning', 'critical'})


if __name__ == '__main__':
    unittest.main()
