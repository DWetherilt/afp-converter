#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def load_paths(dbs: list[tuple[str, Path]]) -> list[str]:
    paths: list[str] = []
    seen: set[str] = set()
    for _realm, db in dbs:
        if not db.exists():
            continue
        with sqlite3.connect(str(db)) as conn:
            for (raw_path,) in conn.execute("select path from code_file_content where trim(path) <> ''"):
                p = str(raw_path or '').replace('\\\\', '/')
                if not p or os.path.isabs(p):
                    continue
                if '..' in Path(p).parts:
                    continue
                if p not in seen:
                    seen.add(p)
                    paths.append(p)
    return paths


def remanifest(paths: list[str], dbs: list[tuple[str, Path]]) -> tuple[int, int, bool, bool]:
    deleted = 0
    for raw in paths:
        p = Path(raw)
        if p.exists() and p.is_file():
            p.unlink()
            deleted += 1

    written = 0
    for _realm, db in dbs:
        if not db.exists():
            continue
        with sqlite3.connect(str(db)) as conn:
            for endpoint, content in conn.execute("select path, content from code_file_content"):
                ep = str(endpoint or '').replace('\\\\', '/')
                if not ep or os.path.isabs(ep):
                    continue
                pp = Path(ep)
                if '..' in pp.parts:
                    continue
                pp.parent.mkdir(parents=True, exist_ok=True)
                pp.write_text(content or '', encoding='utf-8')
                written += 1

    wrapper_restored = Path('gradle/wrapper/gradle-wrapper.properties').exists()
    settings_restored = Path('settings.gradle').exists()
    return deleted, written, wrapper_restored, settings_restored


def main() -> int:
    ap = argparse.ArgumentParser(description='Controlled SQL managed-file delete and remanifest drill.')
    ap.add_argument('--output', required=True)
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--application-db', default='management/pm/state/application-realm.sqlite')
    ap.add_argument('--pm-db', default='management/pm/state/pm-realm.sqlite')
    ap.add_argument('--boilerplate-db', default='management/pm/state/boilerplate-realm.sqlite')
    ap.add_argument('--finalize-status', default='')
    args = ap.parse_args()

    output = Path(args.output)
    ensure_parent(output)

    if args.finalize_status:
        payload = {}
        if output.exists():
            try:
                payload = json.loads(output.read_text(encoding='utf-8'))
            except Exception:
                payload = {}
        payload['finalizedAt'] = now_iso()
        payload['qualityGateStatus'] = args.finalize_status.upper()
        status = payload.get('status', 'PASS')
        payload['status'] = 'PASS' if status == 'PASS' and payload['qualityGateStatus'] == 'PASS' else 'FAIL'
        output.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
        print(f"remanifest_finalize={payload['status']}")
        return 0 if payload['status'] == 'PASS' else 2

    if os.getenv('AFP_REMANIFEST_APPROVED', '').lower() not in {'1', 'true', 'yes'}:
        print('AFP_REMANIFEST_APPROVED is required (set true/1/yes).')
        return 2

    dbs = [
        ('application', Path(args.application_db)),
        ('pm', Path(args.pm_db)),
        ('boilerplate', Path(args.boilerplate_db)),
    ]

    started = now_iso()
    t0 = time.time()
    managed_paths = load_paths(dbs)

    manifest = Path(args.manifest)
    ensure_parent(manifest)
    manifest.write_text('\n'.join(managed_paths) + ('\n' if managed_paths else ''), encoding='utf-8')

    deleted, written, wrapper_restored, settings_restored = remanifest(managed_paths, dbs)
    duration = round(time.time() - t0, 3)
    assertion_pass = (deleted == written)

    payload = {
        'schemaVersion': '1',
        'startedAt': started,
        'finishedAt': now_iso(),
        'durationSec': duration,
        'manifestPath': str(manifest).replace('\\\\', '/'),
        'managedPathCount': len(managed_paths),
        'deletedCount': deleted,
        'rematerializedCount': written,
        'assertions': {
            'deletedEqualsRematerialized': assertion_pass,
            'gradleWrapperRestored': wrapper_restored,
            'settingsGradleRestored': settings_restored,
        },
        'qualityGateStatus': 'PENDING',
        'status': 'PASS' if (assertion_pass and wrapper_restored and settings_restored) else 'FAIL',
    }
    output.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
    print(f"remanifest_status={payload['status']} deleted={deleted} written={written}")
    return 0 if payload['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
