#!/usr/bin/env python3
from __future__ import annotations
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')

def load_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except Exception:
        return {}

def text(value) -> str:
    return str(value or '').strip()

def main() -> int:
    ap = argparse.ArgumentParser(description='Summarize action items by owner.')
    ap.add_argument('--actions', required=True)
    ap.add_argument('--output', required=True)
    args = ap.parse_args()

    actions_payload = load_json(Path(args.actions))
    actions = actions_payload.get('actions', []) if isinstance(actions_payload, dict) else []

    owners: dict[str, dict] = {}
    total_open = 0
    total_stale = 0
    for row in actions:
        owner = text(row.get('owner')) or 'unassigned'
        status = text(row.get('status')).lower()
        stale = bool(row.get('isStale', False))
        bucket = owners.setdefault(owner, {'owner': owner, 'total': 0, 'open': 0, 'stale': 0})
        bucket['total'] += 1
        if status in {'open', 'in_progress', 'blocked'}:
            bucket['open'] += 1
            total_open += 1
        if stale:
            bucket['stale'] += 1
            total_stale += 1

    owner_rows = sorted(owners.values(), key=lambda r: (-r['open'], -r['stale'], r['owner']))
    payload = {
        'schemaVersion': '1',
        'generatedAt': now_iso(),
        'source': args.actions,
        'total': len(actions),
        'open': total_open,
        'stale': total_stale,
        'ownerCount': len(owner_rows),
        'owners': owner_rows,
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
