#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def require_field(obj: dict, key: str) -> None:
    if key not in obj:
        raise ValueError(f"missing field: {key}")


def main() -> int:
    ap = argparse.ArgumentParser(description='Validate environment health report schema.')
    ap.add_argument('--input', required=True)
    args = ap.parse_args()

    path = Path(args.input)
    if not path.exists():
        raise SystemExit(f"missing report: {path}")
    payload = json.loads(path.read_text(encoding='utf-8'))

    for key in ['schemaVersion', 'generatedAt', 'status', 'console', 'visibility', 'duplicates', 'issues']:
        require_field(payload, key)

    console = payload['console']
    visibility = payload['visibility']
    duplicates = payload['duplicates']

    if not isinstance(console, dict) or not isinstance(visibility, dict) or not isinstance(duplicates, dict):
        raise SystemExit('invalid schema: nested objects are malformed')

    for key in ['pidFile', 'running', 'logFile', 'logExists']:
        require_field(console, key)
    for key in ['visibleRunning', 'visibleSessionPids', 'terminalWindowId', 'terminalTabId']:
        require_field(visibility, key)
    for key in ['staleCandidateCount', 'staleCandidatePids']:
        require_field(duplicates, key)

    print('environment_health_schema=PASS')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
