#!/usr/bin/env python3
from __future__ import annotations
import argparse, json
from datetime import datetime, timezone
from pathlib import Path

def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')

def load(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except Exception:
        return {}

def to_map(payload: dict) -> dict[str, dict]:
    out = {}
    for row in payload.get('rules', []):
        rid = str(row.get('ruleId','')).strip()
        if rid:
            out[rid] = row
    return out

def row_changed(a: dict, b: dict) -> bool:
    keys = ['realm','category','ruleText','sourceRef','mutableBy','enabled']
    return any(a.get(k) != b.get(k) for k in keys)

def main() -> int:
    ap = argparse.ArgumentParser(description='Generate realm policy diff against previous snapshot.')
    ap.add_argument('--application', required=True)
    ap.add_argument('--pm', required=True)
    ap.add_argument('--boilerplate', required=True)
    ap.add_argument('--snapshot', required=True)
    ap.add_argument('--output', required=True)
    args = ap.parse_args()

    current = {
        'application': load(Path(args.application)),
        'pm': load(Path(args.pm)),
        'boilerplate': load(Path(args.boilerplate)),
    }
    snapshot_path = Path(args.snapshot)
    previous = load(snapshot_path)
    prev_realms = previous.get('realms', {}) if isinstance(previous, dict) else {}

    realms = {}
    for realm, payload in current.items():
      cur_map = to_map(payload)
      prev_map = to_map(prev_realms.get(realm, {}))
      added = sorted([rid for rid in cur_map if rid not in prev_map])
      removed = sorted([rid for rid in prev_map if rid not in cur_map])
      changed = sorted([rid for rid in cur_map if rid in prev_map and row_changed(cur_map[rid], prev_map[rid])])
      realms[realm] = {
        'currentCount': len(cur_map),
        'previousCount': len(prev_map),
        'added': added,
        'removed': removed,
        'changed': changed,
      }

    report = {
      'schemaVersion':'1',
      'generatedAt': now_iso(),
      'snapshot': str(snapshot_path),
      'realms': realms,
    }

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')

    snapshot_payload = {'schemaVersion':'1','generatedAt': now_iso(), 'realms': current}
    snapshot_path.parent.mkdir(parents=True, exist_ok=True)
    snapshot_path.write_text(json.dumps(snapshot_payload, indent=2) + '\n', encoding='utf-8')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
