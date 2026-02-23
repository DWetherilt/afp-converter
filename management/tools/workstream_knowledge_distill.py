#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def connect(path: Path) -> sqlite3.Connection:
    ensure_parent(path)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def ensure_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        create table if not exists realm_derivation_nodes (
            node_id text primary key,
            parent_node_id text not null default '',
            realm text not null default '',
            layer text not null default '',
            product_id text not null default '',
            role text not null default '',
            lineage_depth integer not null default 0,
            source_ref text not null default '',
            updated_at text not null
        );
        create index if not exists idx_realm_derivation_nodes_parent on realm_derivation_nodes(parent_node_id);
        create index if not exists idx_realm_derivation_nodes_realm on realm_derivation_nodes(realm);

        create table if not exists realm_derivation_edges (
            parent_node_id text not null,
            child_node_id text not null,
            relation text not null default 'derives',
            source_ref text not null default '',
            updated_at text not null,
            primary key(parent_node_id, child_node_id, relation)
        );
        create index if not exists idx_realm_derivation_edges_child on realm_derivation_edges(child_node_id);

        create table if not exists workstream_knowledge (
            workstream_id text not null,
            workstream_label text not null default '',
            workstream_priority_rank integer not null default 999,
            task_key text not null,
            task text not null default '',
            priority text not null default '',
            status text not null default '',
            percent_complete text not null default '',
            last_updated text not null default '',
            notes text not null default '',
            owner_realm text not null default '',
            policy_rule_id text not null default '',
            authority_chain text not null default '',
            write_contract text not null default '',
            secured integer not null default 1,
            source_row_index integer not null default 0,
            source_ref text not null default '',
            updated_at text not null,
            primary key(workstream_id, task_key)
        );
        create index if not exists idx_workstream_knowledge_owner on workstream_knowledge(owner_realm);
        create index if not exists idx_workstream_knowledge_status on workstream_knowledge(status);

        create table if not exists workstream_rollup (
            workstream_id text primary key,
            workstream_label text not null default '',
            workstream_priority_rank integer not null default 999,
            owner_realm text not null default '',
            task_count integer not null default 0,
            complete_count integer not null default 0,
            in_progress_count integer not null default 0,
            not_started_count integer not null default 0,
            avg_percent_complete real not null default 0.0,
            source_ref text not null default '',
            updated_at text not null
        );
        """
    )
    ensure_column(conn, "workstream_knowledge", "workstream_label", "text not null default ''")
    ensure_column(conn, "workstream_knowledge", "workstream_priority_rank", "integer not null default 999")
    ensure_column(conn, "workstream_rollup", "workstream_label", "text not null default ''")
    ensure_column(conn, "workstream_rollup", "workstream_priority_rank", "integer not null default 999")


def ensure_column(conn: sqlite3.Connection, table: str, column: str, ddl: str) -> None:
    cols = {str(r[1]) for r in conn.execute(f"pragma table_info({table})")}
    if column in cols:
        return
    conn.execute(f"alter table {table} add column {column} {ddl}")


def parse_topology(path: Path) -> tuple[list[dict], list[dict], str]:
    if not path.exists():
        return [], [], "human_lead_developer > core_policy_realm > project_policy > project_realms"
    data = json.loads(path.read_text(encoding="utf-8"))
    graph = data.get("realmDerivationGraph", {})
    nodes = graph.get("nodes", [])
    edges = graph.get("edges", [])
    authority = data.get("core", {}).get("authorityOrder", [])
    chain = " > ".join([str(v).strip() for v in authority if str(v).strip()]) or (
        "human_lead_developer > core_policy_realm > project_policy > project_realms"
    )
    return nodes, edges, chain


def parse_percent(raw: str) -> float:
    value = (raw or "").strip()
    if not value:
        return 0.0
    if value.endswith("%"):
        value = value[:-1]
    try:
        n = float(value)
        if n < 0.0:
            return 0.0
        if n > 100.0:
            return 100.0
        return n
    except Exception:
        return 0.0


def normalize_workstream_id(raw: str) -> str:
    value = (raw or "").strip()
    if not value:
        return "UNKNOWN"
    lower = value.lower()
    if lower.startswith("workstream "):
        return value.split(" ", 1)[1].strip().upper()
    return value.upper()


def normalize_uid(raw_uid: str, label: str) -> str:
    uid = (raw_uid or "").strip()
    if uid:
        return uid
    normalized = normalize_workstream_id(label)
    return f"WS-{normalized}" if normalized else "WS-UNKNOWN"


def parse_rank(raw_rank: str, label: str) -> int:
    value = (raw_rank or "").strip()
    if value:
        try:
            n = int(value)
            if n > 0:
                return n
        except Exception:
            pass
    norm = normalize_workstream_id(label)
    if len(norm) == 1 and "A" <= norm <= "Z":
        return ord(norm) - ord("A") + 1
    return 999


def resolve_owner_realm(workstream: str, task: str, notes: str) -> str:
    ws = normalize_workstream_id(workstream)
    text_blob = f"{task} {notes}".lower()

    if "boilerplate" in text_blob:
        return "boilerplate"
    if ws in {"A", "B", "C", "D", "F"}:
        return "application"
    if any(token in text_blob for token in ("renderer", "afp", "font", "image", "graphics", "cli", "api")):
        return "application"
    if any(token in text_blob for token in ("governance", "policy", "workflow", "sqlite", "state", "quality gate")):
        return "pm"
    return "pm"


def load_plan_tasks(conn: sqlite3.Connection) -> list[sqlite3.Row]:
    return list(
        conn.execute(
            """
            select row_index, task_key, workstream, task, priority, status, percent_complete, last_updated, notes
            , coalesce(workstream_uid, '') as workstream_uid
            , coalesce(workstream_label, workstream, '') as workstream_label
            , coalesce(workstream_priority_rank, 999) as workstream_priority_rank
            from plan_tasks
            order by row_index asc
            """
        )
    )


def reset_knowledge_tables(conn: sqlite3.Connection) -> None:
    conn.execute("delete from workstream_knowledge")
    conn.execute("delete from workstream_rollup")


def reset_topology_tables(conn: sqlite3.Connection) -> None:
    conn.execute("delete from realm_derivation_edges")
    conn.execute("delete from realm_derivation_nodes")


def sync_topology(conn: sqlite3.Connection, nodes: Iterable[dict], edges: Iterable[dict], source_ref: str, ts: str) -> int:
    reset_topology_tables(conn)
    count = 0
    for node in nodes:
        conn.execute(
            """
            insert into realm_derivation_nodes(
                node_id, parent_node_id, realm, layer, product_id, role, lineage_depth, source_ref, updated_at
            ) values(?,?,?,?,?,?,?,?,?)
            """,
            (
                str(node.get("nodeId", "")).strip(),
                str(node.get("parentNodeId", "")).strip(),
                str(node.get("realm", "")).strip(),
                str(node.get("layer", "")).strip(),
                str(node.get("productId", "")).strip(),
                str(node.get("role", "")).strip(),
                int(node.get("lineageDepth", 0) or 0),
                source_ref,
                ts,
            ),
        )
        count += 1
    for edge in edges:
        conn.execute(
            """
            insert into realm_derivation_edges(parent_node_id, child_node_id, relation, source_ref, updated_at)
            values(?,?,?,?,?)
            """,
            (
                str(edge.get("parentNodeId", "")).strip(),
                str(edge.get("childNodeId", "")).strip(),
                str(edge.get("relation", "derives")).strip() or "derives",
                source_ref,
                ts,
            ),
        )
    return count


def insert_workstream_rows(
    conn: sqlite3.Connection,
    tasks: list[sqlite3.Row],
    target_realm: str,
    authority_chain: str,
    policy_rule_id: str,
    write_contract: str,
    source_ref: str,
    ts: str,
) -> tuple[int, int]:
    reset_knowledge_tables(conn)
    inserted = 0
    by_workstream: dict[str, list[sqlite3.Row]] = {}

    for row in tasks:
        owner_realm = resolve_owner_realm(str(row["workstream"]), str(row["task"]), str(row["notes"]))
        if target_realm != "core" and owner_realm != target_realm:
            continue
        workstream_label = str(row["workstream_label"] or row["workstream"] or "")
        workstream_id = normalize_uid(str(row["workstream_uid"]), workstream_label)
        workstream_rank = parse_rank(str(row["workstream_priority_rank"]), workstream_label)
        by_workstream.setdefault(workstream_id, []).append(row)
        conn.execute(
            """
            insert into workstream_knowledge(
                workstream_id, workstream_label, workstream_priority_rank, task_key, task, priority, status, percent_complete, last_updated, notes,
                owner_realm, policy_rule_id, authority_chain, write_contract, secured, source_row_index, source_ref, updated_at
            ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                workstream_id,
                workstream_label,
                workstream_rank,
                str(row["task_key"]),
                str(row["task"] or ""),
                str(row["priority"] or ""),
                str(row["status"] or ""),
                str(row["percent_complete"] or ""),
                str(row["last_updated"] or ""),
                str(row["notes"] or ""),
                owner_realm,
                policy_rule_id,
                authority_chain,
                write_contract,
                1,
                int(row["row_index"]),
                source_ref,
                ts,
            ),
        )
        inserted += 1

    for workstream_id, rows in by_workstream.items():
        owners = [resolve_owner_realm(str(r["workstream_label"] or r["workstream"]), str(r["task"]), str(r["notes"])) for r in rows]
        owner_realm = owners[0] if owners else ""
        label = str(rows[0]["workstream_label"] or rows[0]["workstream"] or workstream_id) if rows else workstream_id
        rank = parse_rank(str(rows[0]["workstream_priority_rank"]), label) if rows else 999
        task_count = len(rows)
        complete_count = sum(1 for r in rows if str(r["status"] or "").strip().lower() == "complete")
        in_progress_count = sum(1 for r in rows if str(r["status"] or "").strip().lower() == "in progress")
        not_started_count = sum(1 for r in rows if str(r["status"] or "").strip().lower() == "not started")
        avg_percent = 0.0
        if task_count > 0:
            avg_percent = sum(parse_percent(str(r["percent_complete"])) for r in rows) / task_count
        conn.execute(
            """
            insert into workstream_rollup(
                workstream_id, workstream_label, workstream_priority_rank, owner_realm, task_count, complete_count, in_progress_count, not_started_count,
                avg_percent_complete, source_ref, updated_at
            ) values(?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                workstream_id,
                label,
                rank,
                owner_realm,
                task_count,
                complete_count,
                in_progress_count,
                not_started_count,
                round(avg_percent, 3),
                source_ref,
                ts,
            ),
        )

    return inserted, len(by_workstream)


def sync_target(
    target_db: Path,
    target_realm: str,
    tasks: list[sqlite3.Row],
    nodes: list[dict],
    edges: list[dict],
    authority_chain: str,
    source_ref: str,
) -> dict:
    policy_rule_id = "PM-CORE-003"
    write_contract = "owner-negotiation-required"
    ts = now_iso()
    conn = connect(target_db)
    try:
        ensure_schema(conn)
        topology_count = sync_topology(conn, nodes, edges, source_ref, ts)
        row_count, workstream_count = insert_workstream_rows(
            conn=conn,
            tasks=tasks,
            target_realm=target_realm,
            authority_chain=authority_chain,
            policy_rule_id=policy_rule_id,
            write_contract=write_contract,
            source_ref=source_ref,
            ts=ts,
        )
        conn.commit()
        return {
            "realm": target_realm,
            "db": str(target_db),
            "workstreamKnowledgeRows": row_count,
            "workstreamCount": workstream_count,
            "topologyNodeCount": topology_count,
            "policyRuleId": policy_rule_id,
            "writeContract": write_contract,
            "authorityChain": authority_chain,
        }
    finally:
        conn.close()


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Distill implemented workstream knowledge into distributed realm SQLite stores with topology metadata."
    )
    ap.add_argument("--project-db", required=True)
    ap.add_argument("--core-db", required=True)
    ap.add_argument("--application-db", required=True)
    ap.add_argument("--pm-db", required=True)
    ap.add_argument("--boilerplate-db", required=True)
    ap.add_argument("--topology", required=True)
    ap.add_argument("--report", required=True)
    args = ap.parse_args()

    source_ref = "management/pm/state/project-state.sqlite::plan_tasks"
    nodes, edges, authority_chain = parse_topology(Path(args.topology))
    project_conn = connect(Path(args.project_db))
    try:
        tasks = load_plan_tasks(project_conn)
    finally:
        project_conn.close()

    targets = [
        sync_target(Path(args.core_db), "core", tasks, nodes, edges, authority_chain, source_ref),
        sync_target(Path(args.application_db), "application", tasks, nodes, edges, authority_chain, source_ref),
        sync_target(Path(args.pm_db), "pm", tasks, nodes, edges, authority_chain, source_ref),
        sync_target(Path(args.boilerplate_db), "boilerplate", tasks, nodes, edges, authority_chain, source_ref),
    ]

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": source_ref,
        "topologySource": args.topology,
        "targets": targets,
    }
    report_path = Path(args.report)
    ensure_parent(report_path)
    report_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
