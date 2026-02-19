#!/usr/bin/env python3
import argparse
import json
import sqlite3
from pathlib import Path
from datetime import datetime, timezone


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def connect(path: Path) -> sqlite3.Connection:
    ensure_parent(path)
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    return conn


def init_experience_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        create table if not exists experience_events (
            event_id text primary key,
            knowledge_id text not null default '',
            realm text not null default '',
            scope_level text not null default '',
            scope_ref text not null default '',
            outcome_status text not null default '',
            confidence real not null default 0.0,
            impact_score real not null default 0.0,
            evidence_count integer not null default 0,
            decision_link_count integer not null default 0,
            change_ref text not null default '',
            captured_at text not null,
            notes text not null default ''
        );
        create index if not exists idx_experience_events_realm on experience_events(realm);
        create index if not exists idx_experience_events_outcome on experience_events(outcome_status);

        create table if not exists heuristic_scores (
            heuristic_key text primary key,
            heuristic_group text not null default '',
            score real not null default 0.0,
            sample_count integer not null default 0,
            signal text not null default '',
            updated_at text not null
        );
        """
    )


def init_derived_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        create table if not exists derived_insights (
            insight_id text primary key,
            realm text not null default '',
            insight_type text not null default '',
            statement text not null default '',
            support_score real not null default 0.0,
            source_refs_json text not null default '[]',
            updated_at text not null
        );
        create index if not exists idx_derived_insights_realm on derived_insights(realm);

        create table if not exists insight_edges (
            from_insight_id text not null,
            to_insight_id text not null,
            relation text not null default 'supports',
            updated_at text not null,
            primary key(from_insight_id, to_insight_id)
        );
        """
    )


def load_knowledge(project_conn: sqlite3.Connection):
    return project_conn.execute(
        """
        select
            ke.knowledge_id,
            ke.realm,
            ke.scope_level,
            ke.scope_ref,
            ke.outcome_status,
            ke.confidence,
            ke.impact_score,
            ke.change_ref,
            ke.updated_at,
            (select count(*) from knowledge_evidence ev where ev.knowledge_id = ke.knowledge_id) as evidence_count,
            (select count(*) from knowledge_decision_links dl where dl.knowledge_id = ke.knowledge_id) as decision_link_count,
            ke.reasoning,
            ke.outcome_summary
        from knowledge_entries ke
        order by ke.updated_at desc, ke.knowledge_id asc
        """
    ).fetchall()


def sync_experience(project_conn: sqlite3.Connection, exp_conn: sqlite3.Connection) -> dict:
    rows = load_knowledge(project_conn)
    exp_conn.execute("delete from experience_events")
    exp_conn.execute("delete from heuristic_scores")

    inserted = 0
    for row in rows:
        event_id = f"exp::{row['knowledge_id']}"
        notes = (row["reasoning"] or "").strip()
        outcome_summary = (row["outcome_summary"] or "").strip()
        merged_notes = (notes + " | " + outcome_summary).strip(" |")
        exp_conn.execute(
            """
            insert into experience_events(
                event_id, knowledge_id, realm, scope_level, scope_ref, outcome_status,
                confidence, impact_score, evidence_count, decision_link_count,
                change_ref, captured_at, notes
            ) values(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                event_id,
                row["knowledge_id"] or "",
                row["realm"] or "",
                row["scope_level"] or "",
                row["scope_ref"] or "",
                row["outcome_status"] or "",
                float(row["confidence"] or 0.0),
                float(row["impact_score"] or 0.0),
                int(row["evidence_count"] or 0),
                int(row["decision_link_count"] or 0),
                row["change_ref"] or "",
                row["updated_at"] or now_iso(),
                merged_notes,
            ),
        )
        inserted += 1

    # Heuristic 1: evidence density by realm.
    for realm_row in exp_conn.execute(
        """
        select realm,
               avg(case when impact_score < 0 then 0 else impact_score end) as avg_impact,
               avg(case when evidence_count < 0 then 0 else evidence_count end) as avg_evidence,
               count(*) as samples
        from experience_events
        group by realm
        """
    ).fetchall():
        realm = (realm_row["realm"] or "unknown").strip() or "unknown"
        avg_impact = float(realm_row["avg_impact"] or 0.0)
        avg_evidence = float(realm_row["avg_evidence"] or 0.0)
        samples = int(realm_row["samples"] or 0)
        score = round(min(100.0, (avg_impact * 12.5) + (avg_evidence * 5.0)), 3)
        exp_conn.execute(
            """
            insert into heuristic_scores(heuristic_key, heuristic_group, score, sample_count, signal, updated_at)
            values(?,?,?,?,?,?)
            """,
            (
                f"evidence_density::{realm}",
                "evidence_density",
                score,
                samples,
                f"avg_impact={avg_impact:.3f},avg_evidence={avg_evidence:.3f}",
                now_iso(),
            ),
        )

    # Heuristic 2: closure ratio.
    status_row = exp_conn.execute(
        """
        select
            sum(case when lower(outcome_status) in ('applied','validated') then 1 else 0 end) as positive,
            count(*) as total
        from experience_events
        """
    ).fetchone()
    positive = int(status_row["positive"] or 0)
    total = int(status_row["total"] or 0)
    closure = 0.0 if total == 0 else round((positive / total) * 100.0, 3)
    exp_conn.execute(
        """
        insert into heuristic_scores(heuristic_key, heuristic_group, score, sample_count, signal, updated_at)
        values(?,?,?,?,?,?)
        """,
        (
            "closure_ratio::global",
            "closure_ratio",
            closure,
            total,
            f"positive={positive},total={total}",
            now_iso(),
        ),
    )

    exp_conn.commit()
    return {"event_count": inserted, "positive_count": positive, "total_count": total}


def sync_derived(exp_conn: sqlite3.Connection, drv_conn: sqlite3.Connection) -> dict:
    drv_conn.execute("delete from derived_insights")
    drv_conn.execute("delete from insight_edges")

    inserted = 0

    realm_rows = exp_conn.execute(
        """
        select realm,
               avg(impact_score) as avg_impact,
               avg(confidence) as avg_confidence,
               sum(case when lower(outcome_status) in ('open') then 1 else 0 end) as open_count,
               count(*) as sample_count
        from experience_events
        group by realm
        order by avg_impact desc
        """
    ).fetchall()

    prior_id = None
    for idx, row in enumerate(realm_rows, start=1):
        realm = (row["realm"] or "unknown").strip() or "unknown"
        avg_impact = float(row["avg_impact"] or 0.0)
        avg_conf = float(row["avg_confidence"] or 0.0)
        open_count = int(row["open_count"] or 0)
        samples = int(row["sample_count"] or 0)
        support = round(min(100.0, (avg_impact * 12.5) + (avg_conf * 10.0)), 3)
        insight_id = f"insight::{realm}::{idx}"
        statement = (
            f"Realm '{realm}' has avg impact {avg_impact:.2f}, avg confidence {avg_conf:.2f}, "
            f"open knowledge items {open_count}/{samples}."
        )
        source_refs = json.dumps([f"experience_events::{realm}", "heuristic_scores::evidence_density"], ensure_ascii=True)
        drv_conn.execute(
            """
            insert into derived_insights(insight_id, realm, insight_type, statement, support_score, source_refs_json, updated_at)
            values(?,?,?,?,?,?,?)
            """,
            (insight_id, realm, "realm_signal", statement, support, source_refs, now_iso()),
        )
        inserted += 1
        if prior_id is not None:
            drv_conn.execute(
                "insert into insight_edges(from_insight_id, to_insight_id, relation, updated_at) values(?,?,?,?)",
                (prior_id, insight_id, "precedes", now_iso()),
            )
        prior_id = insight_id

    drv_conn.commit()
    return {"insight_count": inserted}


def export_report(exp_conn: sqlite3.Connection, drv_conn: sqlite3.Connection, path: Path) -> None:
    ensure_parent(path)

    top_heuristics = []
    for row in exp_conn.execute(
        "select heuristic_key, heuristic_group, score, sample_count, signal, updated_at from heuristic_scores order by score desc, heuristic_key asc limit 10"
    ).fetchall():
        top_heuristics.append(
            {
                "heuristicKey": row["heuristic_key"],
                "group": row["heuristic_group"],
                "score": round(float(row["score"] or 0.0), 3),
                "sampleCount": int(row["sample_count"] or 0),
                "signal": row["signal"] or "",
                "updatedAt": row["updated_at"] or "",
            }
        )

    top_insights = []
    for row in drv_conn.execute(
        "select insight_id, realm, insight_type, statement, support_score, source_refs_json, updated_at from derived_insights order by support_score desc, insight_id asc limit 10"
    ).fetchall():
        try:
            refs = json.loads(row["source_refs_json"] or "[]")
            if not isinstance(refs, list):
                refs = []
        except Exception:
            refs = []
        top_insights.append(
            {
                "insightId": row["insight_id"],
                "realm": row["realm"],
                "type": row["insight_type"],
                "statement": row["statement"],
                "supportScore": round(float(row["support_score"] or 0.0), 3),
                "sourceRefs": refs,
                "updatedAt": row["updated_at"] or "",
            }
        )

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": "reasoning-sync",
        "experience": {
            "db": str(exp_conn.execute("pragma database_list").fetchone()[2]),
            "eventCount": int(exp_conn.execute("select count(*) from experience_events").fetchone()[0]),
            "heuristicCount": int(exp_conn.execute("select count(*) from heuristic_scores").fetchone()[0]),
            "topHeuristics": top_heuristics,
        },
        "derived": {
            "db": str(drv_conn.execute("pragma database_list").fetchone()[2]),
            "insightCount": int(drv_conn.execute("select count(*) from derived_insights").fetchone()[0]),
            "topInsights": top_insights,
        },
    }

    path.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync reasoning experience/derived databases and emit reasoning drive report.")
    parser.add_argument("--project-db", required=True)
    parser.add_argument("--experience-db", required=True)
    parser.add_argument("--derived-db", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    project_db = Path(args.project_db)
    experience_db = Path(args.experience_db)
    derived_db = Path(args.derived_db)
    report = Path(args.report)

    with connect(project_db) as project_conn, connect(experience_db) as exp_conn, connect(derived_db) as drv_conn:
        init_experience_schema(exp_conn)
        init_derived_schema(drv_conn)
        exp_stats = sync_experience(project_conn, exp_conn)
        drv_stats = sync_derived(exp_conn, drv_conn)
        export_report(exp_conn, drv_conn, report)

    print(json.dumps({
        "experience": exp_stats,
        "derived": drv_stats,
        "report": str(report)
    }, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
