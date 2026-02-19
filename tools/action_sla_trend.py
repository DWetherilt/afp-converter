#!/usr/bin/env python3
from __future__ import annotations
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')

def today_utc() -> str:
    return datetime.now(timezone.utc).strftime('%Y-%m-%d')

def load_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except Exception:
        return {}

def save_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')

def main() -> int:
    ap = argparse.ArgumentParser(description='Maintain action SLA breach trend history.')
    ap.add_argument('--actions', required=True)
    ap.add_argument('--history', required=True)
    ap.add_argument('--output', required=True)
    args = ap.parse_args()

    actions_payload = load_json(Path(args.actions))
    actions = actions_payload.get('actions', []) if isinstance(actions_payload, dict) else []
    total = len(actions)
    stale = sum(1 for row in actions if bool(row.get('isStale', False)))
    open_count = sum(1 for row in actions if str(row.get('status', '')).lower() in {'open', 'in_progress', 'blocked'})
    breach_ratio = 0.0 if total == 0 else round((stale / total) * 100.0, 3)

    snap = {
        'date': today_utc(),
        'capturedAt': now_iso(),
        'total': total,
        'open': open_count,
        'stale': stale,
        'breachRatioPct': breach_ratio,
    }

    history_path = Path(args.history)
    hist_payload = load_json(history_path)
    rows = hist_payload.get('history', []) if isinstance(hist_payload, dict) else []
    if not isinstance(rows, list):
        rows = []

    replaced = False
    for i, row in enumerate(rows):
        if isinstance(row, dict) and row.get('date') == snap['date']:
            rows[i] = snap
            replaced = True
            break
    if not replaced:
        rows.append(snap)

    rows = sorted([r for r in rows if isinstance(r, dict) and r.get('date')], key=lambda r: r['date'])[-90:]
    history_payload = {
        'schemaVersion': '1',
        'generatedAt': now_iso(),
        'source': args.actions,
        'history': rows,
    }
    save_json(history_path, history_payload)

    output_payload = {
        'schemaVersion': '1',
        'generatedAt': now_iso(),
        'source': args.actions,
        'current': snap,
        'points': rows,
        'pointCount': len(rows),
    }
    save_json(Path(args.output), output_payload)
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
