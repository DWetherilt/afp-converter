#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser(description='Validate pm-console stale history schema.')
    ap.add_argument('--input', required=True)
    args = ap.parse_args()

    path = Path(args.input)
    if not path.exists():
        raise SystemExit(f'missing history file: {path}')
    payload = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(payload, dict):
        raise SystemExit('invalid history schema: root is not object')
    if payload.get('schemaVersion') != '1':
        raise SystemExit('invalid history schema: schemaVersion must be 1')
    events = payload.get('events')
    if not isinstance(events, list):
        raise SystemExit('invalid history schema: events must be list')
    for i, event in enumerate(events):
        if not isinstance(event, dict):
            raise SystemExit(f'invalid history event {i}: expected object')
        for key in ('generatedAt', 'staleCandidateCount', 'status'):
            if key not in event:
                raise SystemExit(f'invalid history event {i}: missing {key}')
    print('stale_history_schema=PASS')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
