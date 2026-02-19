#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

REQUIRED = {
    'ENV_GOVERNANCE_MODE': ['strict', 'relaxed'],
    'PM_CONSOLE_AUTOLAUNCH': ['true', 'false'],
    'PM_CONSOLE_STALE_POLICY': ['report', 'clean'],
    'PM_CONSOLE_STALE_WARN_THRESHOLD': ['2']
}


def main() -> int:
    ap = argparse.ArgumentParser(description='Lint environment policy/default consistency across Gradle and AI policy docs.')
    ap.add_argument('--gradle', required=True)
    ap.add_argument('--policy', required=True)
    args = ap.parse_args()

    gradle = Path(args.gradle).read_text(encoding='utf-8')
    policy = Path(args.policy).read_text(encoding='utf-8')

    missing: list[str] = []
    for var, expected_tokens in REQUIRED.items():
        if var not in gradle:
            missing.append(f'{var}: missing in gradle')
        if var not in policy:
            missing.append(f'{var}: missing in policy')
        for token in expected_tokens:
            if token not in gradle and token not in policy:
                missing.append(f'{var}: expected token not found -> {token}')

    if missing:
        raise SystemExit('environment_policy_defaults_lint=FAIL\n' + '\n'.join(missing))

    print('environment_policy_defaults_lint=PASS')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
