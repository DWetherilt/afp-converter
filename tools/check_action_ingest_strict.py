#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, sqlite3
from pathlib import Path

def text(v: str|None) -> str:
    return (v or '').strip()

def event_sha(line: str) -> str:
    return hashlib.sha256(line.encode('utf-8')).hexdigest()

def main() -> int:
    ap = argparse.ArgumentParser(description='Fail when actionable prompts exist but are not ingested.')
    ap.add_argument('--db', required=True)
    ap.add_argument('--inbox', required=True)
    ap.add_argument('--source', default='pmconsole-live')
    ap.add_argument('--output', required=True)
    args = ap.parse_args()

    inbox = Path(args.inbox)
    expected: list[dict] = []
    if inbox.exists():
        for raw in inbox.read_text(encoding='utf-8').splitlines():
            line = text(raw)
            if not line:
                continue
            try:
                obj = json.loads(line)
            except Exception:
                continue
            if text(obj.get('source')) != text(args.source):
                continue
            prompt = text(obj.get('prompt'))
            if not prompt:
                continue
            expected.append({'eventSha': event_sha(line), 'prompt': prompt})

    conn = sqlite3.connect(args.db)
    found = set()
    try:
        for row in conn.execute('select event_sha from action_inbox_events'):
            found.add(text(row[0]))
    except Exception:
        pass
    finally:
        conn.close()

    missing = [e for e in expected if e['eventSha'] not in found]
    payload = {
        'schemaVersion': '1',
        'source': {'db': args.db, 'inbox': args.inbox, 'sourceFilter': args.source},
        'expectedCount': len(expected),
        'missingCount': len(missing),
        'missing': missing,
        'status': 'PASS' if not missing else 'FAIL',
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')

    if missing:
        print(f"action_ingest_strict=FAIL missing={len(missing)}")
        return 1
    print('action_ingest_strict=PASS')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
