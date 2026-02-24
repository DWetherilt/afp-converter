#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def connect(path: Path) -> sqlite3.Connection:
    ensure_parent(path)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def ensure_knowledge_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        create table if not exists knowledge_entries (
            knowledge_id text primary key,
            realm text not null default '',
            scope_level text not null default 'realm',
            scope_ref text not null default '',
            title text not null default '',
            reasoning text not null default '',
            context_snapshot text not null default '',
            outcome_status text not null default 'open',
            outcome_summary text not null default '',
            confidence real not null default 0.0,
            impact_score real not null default 0.0,
            change_ref text not null default '',
            created_at text not null,
            updated_at text not null
        );
        create index if not exists idx_knowledge_entries_realm on knowledge_entries(realm);
        create index if not exists idx_knowledge_entries_outcome on knowledge_entries(outcome_status);

        create table if not exists knowledge_evidence (
            knowledge_id text not null,
            artifact_path text not null,
            artifact_sha256 text not null default '',
            artifact_size_bytes integer not null default 0,
            evidence_type text not null default 'artifact',
            notes text not null default '',
            captured_at text not null,
            primary key(knowledge_id, artifact_path)
        );
        create index if not exists idx_knowledge_evidence_id on knowledge_evidence(knowledge_id);

        create table if not exists knowledge_decision_links (
            knowledge_id text not null,
            realm text not null default '',
            decision_id text not null,
            relation text not null default 'supports',
            linked_at text not null,
            primary key(knowledge_id, realm, decision_id)
        );
        create index if not exists idx_knowledge_decision_links_id on knowledge_decision_links(knowledge_id);
        """
    )


def target_filter(target_realm: str, source_realm: str) -> bool:
    sr = (source_realm or "").strip().lower()
    tr = (target_realm or "").strip().lower()
    if not tr:
        return False
    if sr == tr:
        return True
    # PM knowledge is cross-cutting governance/process knowledge.
    if sr == "pm":
        return True
    return False


def load_project_rows(project_conn: sqlite3.Connection):
    entries = list(
        project_conn.execute(
            """
            select knowledge_id, realm, scope_level, scope_ref, title, reasoning, context_snapshot, outcome_status,
                   outcome_summary, confidence, impact_score, change_ref, created_at, updated_at
            from knowledge_entries
            order by updated_at desc, knowledge_id asc
            """
        )
    )
    evidence = list(
        project_conn.execute(
            """
            select knowledge_id, artifact_path, artifact_sha256, artifact_size_bytes, evidence_type, notes, captured_at
            from knowledge_evidence
            """
        )
    )
    links = list(
        project_conn.execute(
            """
            select knowledge_id, realm, decision_id, relation, linked_at
            from knowledge_decision_links
            """
        )
    )
    return entries, evidence, links


def sync_target(
    target_realm: str,
    target_db: Path,
    entries: list[sqlite3.Row],
    evidence: list[sqlite3.Row],
    links: list[sqlite3.Row],
) -> dict:
    target_conn = connect(target_db)
    try:
        ensure_knowledge_schema(target_conn)
        selected = [row for row in entries if target_filter(target_realm, str(row["realm"]))]
        ids = {str(row["knowledge_id"]) for row in selected}
        target_conn.execute("delete from knowledge_evidence")
        target_conn.execute("delete from knowledge_decision_links")
        target_conn.execute("delete from knowledge_entries")

        for row in selected:
            target_conn.execute(
                """
                insert into knowledge_entries(
                    knowledge_id, realm, scope_level, scope_ref, title, reasoning, context_snapshot,
                    outcome_status, outcome_summary, confidence, impact_score, change_ref, created_at, updated_at
                ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    row["knowledge_id"],
                    row["realm"],
                    row["scope_level"],
                    row["scope_ref"],
                    row["title"],
                    row["reasoning"],
                    row["context_snapshot"],
                    row["outcome_status"],
                    row["outcome_summary"],
                    row["confidence"],
                    row["impact_score"],
                    row["change_ref"],
                    row["created_at"],
                    row["updated_at"],
                ),
            )

        selected_evidence = [row for row in evidence if str(row["knowledge_id"]) in ids]
        for row in selected_evidence:
            target_conn.execute(
                """
                insert into knowledge_evidence(
                    knowledge_id, artifact_path, artifact_sha256, artifact_size_bytes, evidence_type, notes, captured_at
                ) values(?,?,?,?,?,?,?)
                """,
                (
                    row["knowledge_id"],
                    row["artifact_path"],
                    row["artifact_sha256"],
                    row["artifact_size_bytes"],
                    row["evidence_type"],
                    row["notes"],
                    row["captured_at"],
                ),
            )

        selected_links = [row for row in links if str(row["knowledge_id"]) in ids]
        for row in selected_links:
            target_conn.execute(
                """
                insert into knowledge_decision_links(knowledge_id, realm, decision_id, relation, linked_at)
                values(?,?,?,?,?)
                """,
                (
                    row["knowledge_id"],
                    row["realm"],
                    row["decision_id"],
                    row["relation"],
                    row["linked_at"],
                ),
            )

        resolved = 0
        unresolved = 0
        unresolved_ids: list[str] = []
        has_decision_log = bool(
            target_conn.execute("select count(*) from sqlite_master where type='table' and name='decision_log'").fetchone()[0]
        )
        if has_decision_log:
            for row in selected_links:
                exists = target_conn.execute(
                    "select count(*) from decision_log where decision_id = ?",
                    (row["decision_id"],),
                ).fetchone()[0]
                if exists:
                    resolved += 1
                else:
                    unresolved += 1
                    unresolved_ids.append(str(row["decision_id"]))

        target_conn.commit()
        return {
            "realm": target_realm,
            "db": str(target_db),
            "knowledgeCount": len(selected),
            "evidenceCount": len(selected_evidence),
            "decisionLinkCount": len(selected_links),
            "resolvedDecisionLinks": resolved,
            "unresolvedDecisionLinks": unresolved,
            "unresolvedDecisionIds": sorted(set(unresolved_ids)),
        }
    finally:
        target_conn.close()


def main() -> int:
    ap = argparse.ArgumentParser(description="Mirror project knowledge base into per-realm databases.")
    ap.add_argument("--project-db", required=True)
    ap.add_argument("--application-db", required=True)
    ap.add_argument("--pm-db", required=True)
    ap.add_argument("--boilerplate-db", required=True)
    ap.add_argument("--report", required=True)
    args = ap.parse_args()

    project_conn = connect(Path(args.project_db))
    try:
        entries, evidence, links = load_project_rows(project_conn)
    finally:
        project_conn.close()

    targets = [
        sync_target("application", Path(args.application_db), entries, evidence, links),
        sync_target("pm", Path(args.pm_db), entries, evidence, links),
        sync_target("boilerplate", Path(args.boilerplate_db), entries, evidence, links),
    ]

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": args.project_db,
        "targets": targets,
    }
    report = Path(args.report)
    ensure_parent(report)
    report.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
