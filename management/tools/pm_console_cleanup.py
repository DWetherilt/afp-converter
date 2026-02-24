#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')


def run_command(cmd: list[str]) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
        return proc.returncode, proc.stdout.strip(), proc.stderr.strip()
    except Exception as exc:
        return 127, '', str(exc)


def read_pid(path: Path) -> int | None:
    if not path.exists():
        return None
    try:
        return int(path.read_text(encoding='utf-8').strip())
    except Exception:
        return None


def collect_live_processes() -> list[dict[str, Any]]:
    rc, out, _ = run_command(['ps', '-axo', 'pid=,tty=,command='])
    if rc != 0:
        return []
    rows: list[dict[str, Any]] = []
    for raw in out.splitlines():
        line = raw.strip()
        if not line:
            continue
        m = re.match(r'^(\d+)\s+(\S+)\s+(.*)$', line)
        if not m:
            continue
        pid = int(m.group(1))
        tty = m.group(2)
        cmd = m.group(3)
        lower = cmd.lower()
        if 'pmconsolemain live' not in lower and 'pmconsole/bin/pmconsole live' not in lower:
            continue
        rows.append({'pid': pid, 'tty': tty, 'command': cmd, 'visible': tty not in {'??', '?'}})
    return rows


def kill_pid(pid: int) -> str:
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return 'missing'
    except Exception as exc:
        return f'term-failed:{exc}'
    time.sleep(0.25)
    try:
        os.kill(pid, 0)
    except OSError:
        return 'terminated'
    try:
        os.kill(pid, signal.SIGKILL)
        return 'killed'
    except Exception as exc:
        return f'kill-failed:{exc}'


def main() -> int:
    ap = argparse.ArgumentParser(description='Cleanup stale duplicate pmconsole live sessions.')
    ap.add_argument('--pid-file', required=True)
    ap.add_argument('--output', required=True)
    ap.add_argument('--history', required=False, default='management/pm/state/pm-console-stale-history.json')
    ap.add_argument('--warn-threshold', required=False, default='2')
    ap.add_argument('--apply', action='store_true')
    args = ap.parse_args()

    pid_file = Path(args.pid_file)
    keep_pid = read_pid(pid_file)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    history_file = Path(args.history)
    history_file.parent.mkdir(parents=True, exist_ok=True)
    try:
        warn_threshold = max(1, int(str(args.warn_threshold).strip()))
    except Exception:
        warn_threshold = 2

    processes = collect_live_processes()
    background = [p for p in processes if not p['visible']]
    stale = [p for p in background if keep_pid is None or p['pid'] != keep_pid]

    actions: list[dict[str, Any]] = []
    if args.apply:
        for p in stale:
            result = kill_pid(p['pid'])
            actions.append({'pid': p['pid'], 'result': result})

    history: dict[str, Any] = {'schemaVersion': '1', 'events': []}
    if history_file.exists():
        try:
            loaded = json.loads(history_file.read_text(encoding='utf-8'))
            if isinstance(loaded, dict):
                history = loaded
        except Exception:
            history = {'schemaVersion': '1', 'events': []}
    events = history.get('events', [])
    if not isinstance(events, list):
        events = []
    events.append({
        'generatedAt': now_iso(),
        'staleCandidateCount': len(stale),
        'status': 'PASS' if len(stale) == 0 else ('CLEANED' if args.apply else 'STALE_DETECTED')
    })
    events = events[-50:]
    history['events'] = events
    history_file.write_text(json.dumps(history, indent=2) + '\n', encoding='utf-8')
    consecutive = 0
    for event in reversed(events):
        if int(event.get('staleCandidateCount', 0)) > 0:
            consecutive += 1
        else:
            break
    warning = ''
    if consecutive >= warn_threshold:
        warning = f'stale-duplicates-persisting:{consecutive}-cycles'

    payload = {
        'schemaVersion': '1',
        'generatedAt': now_iso(),
        'pidFile': str(pid_file).replace('\\', '/'),
        'keepPid': keep_pid if keep_pid is not None else '',
        'historyFile': str(history_file).replace('\\', '/'),
        'warnThreshold': warn_threshold,
        'consecutiveStaleCycles': consecutive,
        'warning': warning,
        'apply': args.apply,
        'liveProcessCount': len(processes),
        'backgroundSessionCount': len(background),
        'staleCandidateCount': len(stale),
        'staleCandidatePids': [p['pid'] for p in stale],
        'actions': actions,
        'status': 'PASS' if len(stale) == 0 else ('CLEANED' if args.apply else 'STALE_DETECTED')
    }
    out.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
    print(f"pm_console_cleanup status={payload['status']} stale={len(stale)} apply={str(args.apply).lower()}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
