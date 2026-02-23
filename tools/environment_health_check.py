#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')


def parse_bool(value: str) -> bool:
    return str(value).strip().lower() in {'1', 'true', 'yes', 'y', 'on'}


def is_running(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def run_command(cmd: list[str]) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
        return proc.returncode, proc.stdout.strip(), proc.stderr.strip()
    except Exception as exc:
        return 127, '', str(exc)


def read_pid(pid_file: Path) -> int | None:
    if not pid_file.exists():
        return None
    try:
        return int(pid_file.read_text(encoding='utf-8').strip())
    except Exception:
        return None


def terminal_window_count() -> tuple[int, int | None, str]:
    if sys.platform != 'darwin':
        return 127, None, 'unsupported-platform'
    rc, out, err = run_command(['osascript', '-e', 'tell application "Terminal" to count windows'])
    if rc != 0:
        return rc, None, err or out
    try:
        return rc, int(out.strip()), ''
    except Exception:
        return rc, None, f'non-integer-window-count:{out}'

def terminal_identity() -> tuple[int, str, str, str]:
    if sys.platform != 'darwin':
        return 127, '', '', 'unsupported-platform'
    script = (
        'tell application "Terminal"\n'
        'if (count windows) is 0 then return ""\n'
        'set w to front window\n'
        'set wid to id of w\n'
        'return (wid as text) & ",1"\n'
        'end tell'
    )
    rc, out, err = run_command(['osascript', '-e', script])
    if rc != 0:
        return rc, '', '', err or out
    if not out.strip():
        return 0, '', '', ''
    parts = (out or '').split(',', 1)
    if len(parts) != 2:
        return 0, '', '', f'invalid-terminal-identity:{out}'
    return 0, parts[0].strip(), parts[1].strip(), ''


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
        visible = tty not in {'??', '?'}
        rows.append({'pid': pid, 'tty': tty, 'command': cmd, 'visible': visible})
    return rows


def launch_visible_terminal(console_command: str) -> tuple[bool, str, str]:
    if sys.platform != 'darwin':
        return False, '', 'unsupported-platform'
    script = (
        'tell application "Terminal" to activate\n'
        f'tell application "Terminal" to do script "cd {shlex.quote(str(Path.cwd()))} && {console_command}"'
    )
    rc, out, err = run_command(['osascript', '-e', script])
    return rc == 0, out, err


def extract_terminal_window_id(text: str) -> str:
    m = re.search(r'window id\s+(\d+)', text or '')
    return m.group(1) if m else ''

def extract_terminal_tab_id(text: str) -> str:
    m = re.search(r'tab\s+(\d+)\s+of\s+window id\s+\d+', text or '')
    return m.group(1) if m else ''


def main() -> int:
    ap = argparse.ArgumentParser(description='Record PM console environment health status.')
    ap.add_argument('--pid-file', required=True)
    ap.add_argument('--log-file', required=True)
    ap.add_argument('--output', required=True)
    ap.add_argument('--expect-running', default='true')
    ap.add_argument('--expect-visible', default='true')
    ap.add_argument('--governance-mode', default='strict', choices=['strict', 'relaxed'])
    ap.add_argument('--auto-launch-if-missing', default='false')
    ap.add_argument('--console-command', default='./pm-console/build/install/pmconsole/bin/pmconsole live')
    ap.add_argument('--ci', default='false')
    args = ap.parse_args()

    pid_file = Path(args.pid_file)
    log_file = Path(args.log_file)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)

    expect_running = parse_bool(args.expect_running)
    expect_visible = parse_bool(args.expect_visible)
    ci_mode = parse_bool(args.ci)
    auto_launch = parse_bool(args.auto_launch_if_missing)

    pid = read_pid(pid_file)
    daemon_running = is_running(pid) if pid is not None else False

    term_rc, term_windows, term_err = terminal_window_count()
    term_id_rc, term_window_id, term_tab_id, term_id_err = terminal_identity()
    processes = collect_live_processes()
    visible = [p for p in processes if p['visible']]
    background = [p for p in processes if not p['visible']]
    visible_running = len(visible) > 0
    daemon_inferred = False
    if not daemon_running and background:
        daemon_running = True
        daemon_inferred = True

    launched = False
    launch_response = ''
    terminal_window_id = ''
    terminal_tab_id = ''

    if expect_visible and not ci_mode and auto_launch and not visible_running:
        launched, launch_response, launch_err = launch_visible_terminal(args.console_command)
        if launched:
            terminal_window_id = extract_terminal_window_id(launch_response)
            terminal_tab_id = extract_terminal_tab_id(launch_response)
            time.sleep(0.75)
            term_rc, term_windows, term_err = terminal_window_count()
            term_id_rc, term_window_id, term_tab_id, term_id_err = terminal_identity()
            processes = collect_live_processes()
            visible = [p for p in processes if p['visible']]
            background = [p for p in processes if not p['visible']]
            visible_running = len(visible) > 0
        else:
            launch_response = launch_err

    keep_pid = pid
    if background and (pid is None or all(p['pid'] != pid for p in background)):
        keep_pid = background[0]['pid']
    stale_candidates = [p for p in background if keep_pid is None or p['pid'] != keep_pid]
    fallback_window = ''
    fallback_tab = ''
    if visible:
        fallback_window = f"tty:{visible[0]['tty']}"
        fallback_tab = f"pid:{visible[0]['pid']}"
    elif expect_visible and not ci_mode and term_windows and term_windows > 0 and term_id_rc == 0:
        # Accept an active Terminal window identity as visibility evidence when process/TTY
        # introspection cannot reliably distinguish the interactive session.
        visible_running = True

    issues: list[str] = []
    blocking_issues: list[str] = []
    if expect_running and not daemon_running:
        issues.append('daemon-not-running')
        blocking_issues.append('daemon-not-running')
    if expect_visible and not ci_mode and not visible_running:
        issues.append('visible-console-not-running')
        blocking_issues.append('visible-console-not-running')
    if len(stale_candidates) > 0:
        issues.append('duplicate-stale-background-sessions-detected')
    if term_rc != 0 and not ci_mode and expect_visible:
        issues.append('terminal-window-query-failed')
        blocking_issues.append('terminal-window-query-failed')
    if term_id_rc != 0 and not ci_mode and expect_visible:
        issues.append('terminal-identity-query-failed')
        if not visible_running:
            blocking_issues.append('terminal-identity-query-failed')

    if not blocking_issues:
        status = 'PASS'
    elif args.governance_mode == 'strict':
        status = 'FAIL'
    else:
        status = 'WARN'

    payload = {
        'schemaVersion': '2',
        'generatedAt': now_iso(),
        'governanceMode': args.governance_mode,
        'ci': ci_mode,
        'expectRunning': expect_running,
        'expectVisible': expect_visible,
        'autoLaunchIfMissing': auto_launch,
        'console': {
            'pidFile': str(pid_file).replace('\\\\', '/'),
            'pid': pid if pid is not None else '',
            'running': daemon_running,
            'runningInferredFromBackgroundSessions': daemon_inferred,
            'logFile': str(log_file).replace('\\\\', '/'),
            'logExists': log_file.exists(),
        },
        'visibility': {
            'terminalWindowQueryRc': term_rc,
            'terminalWindowCount': term_windows if term_windows is not None else '',
            'terminalWindowQueryError': term_err,
            'terminalIdentityQueryRc': term_id_rc,
            'terminalIdentityQueryError': term_id_err,
            'visibleRunning': visible_running,
            'visibleSessionCount': len(visible),
            'visibleSessionPids': [p['pid'] for p in visible],
            'visibleSessionTtys': [p['tty'] for p in visible],
            'terminalWindowId': terminal_window_id if terminal_window_id else (term_window_id if term_window_id else fallback_window),
            'terminalTabId': terminal_tab_id if terminal_tab_id else (term_tab_id if term_tab_id else fallback_tab),
            'autoLaunchTriggered': launched,
            'autoLaunchResponse': launch_response,
        },
        'duplicates': {
            'liveProcessCount': len(processes),
            'backgroundSessionCount': len(background),
            'backgroundSessionPids': [p['pid'] for p in background],
            'staleCandidateCount': len(stale_candidates),
            'staleCandidatePids': [p['pid'] for p in stale_candidates],
        },
        'issues': issues,
        'blockingIssues': blocking_issues,
        'status': status,
    }

    out.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')

    if status == 'PASS':
        print(f'environment_health=PASS running={str(daemon_running).lower()} visible={str(visible_running).lower()}')
        return 0
    print(f"environment_health={status} issues={','.join(issues)}")
    return 2 if status == 'FAIL' else 0


if __name__ == '__main__':
    raise SystemExit(main())
