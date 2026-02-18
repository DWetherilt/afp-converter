#!/usr/bin/env python3
import argparse
import csv
import datetime as dt
import json
from pathlib import Path
from typing import Dict, List


def load_json(path: Path) -> Dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def load_issues(path: Path) -> List[Dict[str, str]]:
    if not path.exists():
        return []
    with path.open("r", encoding="utf-8", newline="") as f:
        return [{k: (v or "").strip() for k, v in row.items()} for row in csv.DictReader(f)]


def load_progress(path: Path) -> List[Dict[str, str]]:
    if not path.exists():
        return []
    with path.open("r", encoding="utf-8", newline="") as f:
        return [{k: (v or "").strip() for k, v in row.items()} for row in csv.DictReader(f)]


def parse_percent(value: str) -> float:
    text = (value or "").strip().replace("%", "")
    try:
        return float(text)
    except Exception:
        return 0.0


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate mechanized issue effectiveness signals.")
    parser.add_argument("--issues", required=True, help="Path to docs/issues-log.csv")
    parser.add_argument("--tickle", required=True, help="Path to preview/issues-log-tickle.json")
    parser.add_argument("--progress", required=True, help="Path to docs/project-plan-progress.csv")
    parser.add_argument("--fidelity", required=True, help="Path to preview/fidelity-report.json")
    parser.add_argument("--output", required=True, help="Path to output JSON")
    args = parser.parse_args()

    issues = load_issues(Path(args.issues))
    tickle = load_json(Path(args.tickle))
    progress = load_progress(Path(args.progress))
    fidelity = load_json(Path(args.fidelity))

    open_issues = [i for i in issues if i.get("status", "").lower() in {"open", "in progress", "active"}]
    high_open = [i for i in open_issues if i.get("severity", "").lower() == "high"]

    percents = [parse_percent(r.get("percent_complete", "")) for r in progress if r.get("task", "")]
    avg_percent = (sum(percents) / len(percents)) if percents else 0.0

    fidelity_score = fidelity.get("fidelityScore")
    pixel_diff = fidelity.get("averagePixelDiffRatio")
    token_recall = fidelity.get("tokenRecall")
    token_precision = fidelity.get("tokenPrecision")

    triggered = tickle.get("triggeredIssues") or []
    requires_update = bool(tickle.get("requiresIssuesLogUpdate", False))

    signals: List[str] = []
    if requires_update:
        signals.append("Issue log update required for changed issue-referenced components.")
    if isinstance(fidelity_score, (int, float)) and fidelity_score < 0.95:
        signals.append("Fidelity score below target (0.95).")
    if isinstance(pixel_diff, (int, float)) and pixel_diff > 0.03:
        signals.append("Average pixel diff ratio above target (0.03).")
    if isinstance(token_recall, (int, float)) and token_recall < 0.98:
        signals.append("Token recall below target (0.98).")
    if isinstance(token_precision, (int, float)) and token_precision < 0.98:
        signals.append("Token precision below target (0.98).")
    if avg_percent < 85.0:
        signals.append("Average plan task completion below 85%.")
    if len(high_open) > 0:
        signals.append("High-severity issues remain open.")

    payload = {
        "schemaVersion": "1",
        "generatedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "summary": {
            "issueCount": len(issues),
            "openIssueCount": len(open_issues),
            "highSeverityOpenCount": len(high_open),
            "triggeredIssueCount": len(triggered),
            "requiresIssuesLogUpdate": requires_update,
            "taskCount": len([r for r in progress if r.get("task", "")]),
            "averageTaskPercentComplete": round(avg_percent, 2),
        },
        "fidelity": {
            "fidelityScore": fidelity_score,
            "averagePixelDiffRatio": pixel_diff,
            "tokenRecall": token_recall,
            "tokenPrecision": token_precision,
        },
        "signals": signals,
        "triggeredIssues": triggered,
        "openHighSeverityIssues": [
            {
                "id": i.get("id", ""),
                "title": i.get("title", ""),
                "owner": i.get("owner", ""),
                "last_reviewed": i.get("last_reviewed", ""),
            }
            for i in high_open
        ],
    }

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
