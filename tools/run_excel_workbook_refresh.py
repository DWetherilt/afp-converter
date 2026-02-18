#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path
from typing import Optional


def run(cmd: list[str], allow_failure: bool = False, timeout_seconds: Optional[int] = None) -> tuple[bool, str]:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        if allow_failure:
            return False, f"timeout after {timeout_seconds}s"
        raise SystemExit(f"Command timed out ({' '.join(cmd)}) after {timeout_seconds}s")
    if proc.returncode != 0:
        out = (proc.stdout or "").strip()
        err = (proc.stderr or "").strip()
        details = "\n".join(x for x in [out, err] if x)
        if allow_failure:
            return False, details
        raise SystemExit(f"Command failed ({' '.join(cmd)}):\n{details}")
    return True, ""


def main() -> int:
    parser = argparse.ArgumentParser(description="Refresh project plan workbook through native Excel scripting.")
    parser.add_argument("--xlsx", required=True, help="Path to docs/project-plan-progress.xlsx")
    parser.add_argument("--json", required=True, help="Path to preview/project-plan-progress-data.json")
    parser.add_argument(
        "--script",
        default="tools/run_excel_workbook_refresh.applescript",
        help="AppleScript path",
    )
    args = parser.parse_args()

    xlsx = Path(args.xlsx).resolve()
    json_path = Path(args.json).resolve()
    script = Path(args.script).resolve()

    if not xlsx.exists():
        raise SystemExit(f"Missing workbook: {xlsx}")
    if not json_path.exists():
        raise SystemExit(f"Missing JSON payload: {json_path}")
    if not script.exists():
        raise SystemExit(f"Missing AppleScript: {script}")

    excel_required = (os.getenv("AFP_EXCEL_REQUIRED", "").strip().lower() in {"1", "true", "yes"})
    excel_ok = False
    excel_error = ""
    if shutil.which("osascript") is not None:
        excel_ok, excel_error = run(
            ["osascript", str(script), str(xlsx)],
            allow_failure=True,
            timeout_seconds=20,
        )
        if excel_ok and workbook_has_required_sheets(xlsx):
            write_status("excel", True, "")
            return 0

    if excel_required:
        raise SystemExit(f"Excel-native refresh failed and fallback is disabled: {excel_error or 'unknown error'}")

    csv_path = extract_source_csv(json_path)
    run_xml_fallback(xlsx, csv_path)
    write_status("xml-fallback", excel_ok, excel_error)
    return 0


def workbook_has_required_sheets(xlsx: Path) -> bool:
    import zipfile
    import xml.etree.ElementTree as ET

    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    required = {"Current Progress", "Status History", "Completion Trend", "Progress Graph"}
    with zipfile.ZipFile(xlsx, "r") as zf:
        wb = ET.fromstring(zf.read("xl/workbook.xml"))
    sheets = wb.find("m:sheets", ns)
    names = set()
    if sheets is not None:
        names = {s.attrib.get("name", "") for s in list(sheets)}
    return required.issubset(names)


def extract_source_csv(json_path: Path) -> Path:
    payload = json.loads(json_path.read_text(encoding="utf-8"))
    source = payload.get("sourceCsv")
    if not isinstance(source, str) or not source.strip():
        raise SystemExit("Missing sourceCsv in progress JSON payload; cannot run XML fallback.")
    source_path = Path(source)
    if not source_path.is_absolute():
        source_path = (json_path.parent.parent / source_path).resolve()
    if not source_path.exists():
        raise SystemExit(f"CSV source from JSON does not exist: {source_path}")
    return source_path


def run_xml_fallback(xlsx: Path, csv_path: Path) -> None:
    fallback_cmd = [
        "python3",
        str((Path(__file__).resolve().parent / "update_project_plan_workbook.py")),
        "--csv",
        str(csv_path),
        "--xlsx",
        str(xlsx),
        "--human-approved",
        "I_HAVE_EXPLICIT_HUMAN_APPROVAL",
    ]
    template_zip = xlsx.with_suffix(xlsx.suffix + ".zip")
    if template_zip.exists():
        fallback_cmd.extend(["--filter-template", str(template_zip)])
    ok, details = run(fallback_cmd)
    if not ok:
        raise SystemExit(details or "XML fallback failed")


def write_status(mode: str, excel_ok: bool, excel_error: str) -> None:
    payload = {
        "schemaVersion": "1",
        "modeUsed": mode,
        "excelSucceeded": excel_ok,
        "excelError": excel_error,
    }
    out = Path("preview/workbook-refresh-status.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
