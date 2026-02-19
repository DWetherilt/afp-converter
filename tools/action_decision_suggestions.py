#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def tokens(text: str) -> set[str]:
    return {t for t in re.split(r"[^a-zA-Z0-9]+", (text or "").lower()) if len(t) >= 4}


def main() -> int:
    ap = argparse.ArgumentParser(description="Build action->decision link suggestions.")
    ap.add_argument("--actions", required=True)
    ap.add_argument("--decisions", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    actions = load_json(Path(args.actions)).get("actions", [])
    decisions = load_json(Path(args.decisions)).get("decisions", [])

    out_rows: list[dict] = []
    for action in actions:
        action_id = str(action.get("actionId", "")).strip()
        realm = str(action.get("realm", "")).strip().lower()
        title = str(action.get("title", "")).strip()
        status = str(action.get("status", "")).strip().lower()
        if not action_id or status in {"done", "cancelled"}:
            continue
        action_tokens = tokens(title)
        best = []
        for decision in decisions:
            d_realm = str(decision.get("realm", "")).strip().lower()
            d_id = str(decision.get("decisionId", "")).strip()
            d_title = str(decision.get("title", "")).strip()
            if not d_id:
                continue
            score = 0.0
            if realm and d_realm == realm:
                score += 2.0
            overlap = len(action_tokens.intersection(tokens(d_title)))
            score += overlap * 1.0
            try:
                impact = float(decision.get("impactScore", 0.0) or 0.0)
            except Exception:
                impact = 0.0
            score += min(impact / 100.0, 1.0)
            if score <= 0.5:
                continue
            best.append({
                "decisionId": d_id,
                "realm": d_realm,
                "title": d_title,
                "score": round(score, 3),
                "impactScore": impact,
            })
        best.sort(key=lambda x: (-x["score"], -x["impactScore"], x["decisionId"]))
        out_rows.append({
            "actionId": action_id,
            "realm": realm,
            "title": title,
            "status": status,
            "suggestions": best[:5],
        })

    payload = {
        "schemaVersion": "1",
        "generatedAt": now_iso(),
        "source": {
            "actions": args.actions,
            "decisions": args.decisions,
        },
        "itemCount": len(out_rows),
        "suggestions": out_rows,
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
