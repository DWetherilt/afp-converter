#!/usr/bin/env python3
from __future__ import annotations

import json
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path


class CheckActionIngestStrictTests(unittest.TestCase):
    def test_fails_when_inbox_event_missing_from_db(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            db = root / 'project.sqlite'
            inbox = root / 'inbox.ndjson'
            out = root / 'out.json'

            conn = sqlite3.connect(db)
            conn.execute('create table action_inbox_events(event_sha text primary key, source text, prompt_text text, captured_at text, ingested_at text, action_id text)')
            conn.commit()
            conn.close()

            inbox.write_text(json.dumps({'source': 'pmconsole-live', 'prompt': 'test prompt', 'capturedAt': '2026-02-19T00:00:00Z'}) + '\n', encoding='utf-8')

            cmd = [
                'python3',
                'management/tools/check_action_ingest_strict.py',
                '--db', str(db),
                '--inbox', str(inbox),
                '--source', 'pmconsole-live',
                '--output', str(out),
            ]
            proc = subprocess.run(cmd, check=False, capture_output=True, text=True)
            self.assertNotEqual(proc.returncode, 0)
            payload = json.loads(out.read_text(encoding='utf-8'))
            self.assertEqual(payload['status'], 'FAIL')
            self.assertEqual(payload['missingCount'], 1)


if __name__ == '__main__':
    unittest.main()
