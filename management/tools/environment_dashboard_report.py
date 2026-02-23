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
        value = json.loads(path.read_text(encoding='utf-8'))
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def main() -> int:
    ap = argparse.ArgumentParser(description='Generate compact environment dashboard report.')
    ap.add_argument('--environment-health', required=True)
    ap.add_argument('--stale-cleanup', required=True)
    ap.add_argument('--output', required=True)
    ap.add_argument('--policy', default='report')
    ap.add_argument('--warn-threshold', default='2')
    args = ap.parse_args()

    health = load_json(Path(args.environment_health))
    cleanup = load_json(Path(args.stale_cleanup))
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)

    visibility = health.get('visibility', {}) if isinstance(health.get('visibility'), dict) else {}
    duplicates = health.get('duplicates', {}) if isinstance(health.get('duplicates'), dict) else {}

    payload = {
        'schemaVersion': '1',
        'generatedAt': now_iso(),
        'status': health.get('status', 'UNKNOWN'),
        'governanceMode': health.get('governanceMode', ''),
        'visibleRunning': visibility.get('visibleRunning', False),
        'visibleSessionCount': visibility.get('visibleSessionCount', 0),
        'terminalWindowId': visibility.get('terminalWindowId', ''),
        'terminalTabId': visibility.get('terminalTabId', ''),
        'staleCandidateCount': cleanup.get('staleCandidateCount', duplicates.get('staleCandidateCount', 0)),
        'staleWarning': cleanup.get('warning', ''),
        'stalePolicy': args.policy,
        'staleWarnThreshold': args.warn_threshold,
        'issues': health.get('issues', []),
        'blockingIssues': health.get('blockingIssues', [])
    }
    out.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
    print('environment_dashboard=PASS')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
