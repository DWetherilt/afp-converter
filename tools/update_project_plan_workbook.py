#!/usr/bin/env python3
import argparse
import csv
import datetime as dt
import io
import posixpath
import re
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from xml.etree import ElementTree as ET


NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
NS_REL_PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
NS_REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
NS_APP = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
NS_VT = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"


def qn(local: str) -> str:
    return f"{{{NS_MAIN}}}{local}"


def rel_qn(local: str) -> str:
    return f"{{{NS_REL_PKG}}}{local}"


def ct_qn(local: str) -> str:
    return f"{{{NS_CT}}}{local}"


def app_qn(local: str) -> str:
    return f"{{{NS_APP}}}{local}"


def vt_qn(local: str) -> str:
    return f"{{{NS_VT}}}{local}"


def col_letter(index: int) -> str:
    out: List[str] = []
    n = index
    while n > 0:
        n, r = divmod(n - 1, 26)
        out.append(chr(ord("A") + r))
    return "".join(reversed(out))


def col_index(cell_ref: str) -> int:
    m = re.match(r"([A-Z]+)", cell_ref or "")
    if not m:
        return 1
    letters = m.group(1)
    val = 0
    for ch in letters:
        val = val * 26 + (ord(ch) - ord("A") + 1)
    return val


def load_csv_rows(csv_path: Path) -> List[Dict[str, str]]:
    with csv_path.open("r", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def parse_xml(data: bytes) -> ET.Element:
    return ET.fromstring(data)


def xml_bytes(root: ET.Element) -> bytes:
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def sheet_path_by_name(zf: zipfile.ZipFile, sheet_name: str) -> Optional[str]:
    workbook_xml = parse_xml(zf.read("xl/workbook.xml"))
    rels_xml = parse_xml(zf.read("xl/_rels/workbook.xml.rels"))
    rel_by_id: Dict[str, str] = {}
    for rel in rels_xml.findall(rel_qn("Relationship")):
        rid = rel.attrib.get("Id")
        target = rel.attrib.get("Target")
        if rid and target:
            rel_by_id[rid] = target
    sheets = workbook_xml.find(qn("sheets"))
    if sheets is None:
        return None
    for sheet in sheets.findall(qn("sheet")):
        if sheet.attrib.get("name") != sheet_name:
            continue
        rid = sheet.attrib.get(f"{{{NS_REL_DOC}}}id")
        target = rel_by_id.get(rid or "")
        if not target:
            return None
        if target.startswith("/"):
            return target.lstrip("/")
        if target.startswith("xl/"):
            return target
        return f"xl/{target}"
    return None


def ensure_cell(row: ET.Element, cidx: int, ridx: int) -> ET.Element:
    target_ref = f"{col_letter(cidx)}{ridx}"
    existing = row.findall(qn("c"))
    for c in existing:
        if c.attrib.get("r") == target_ref:
            return c
    cell = ET.Element(qn("c"), {"r": target_ref, "t": "inlineStr"})
    inserted = False
    for i, c in enumerate(existing):
        if col_index(c.attrib.get("r", "")) > cidx:
            row.insert(i, cell)
            inserted = True
            break
    if not inserted:
        row.append(cell)
    return cell


def set_inline_cell(cell: ET.Element, value: str) -> None:
    cell.attrib["t"] = "inlineStr"
    for child in list(cell):
        cell.remove(child)
    is_node = ET.SubElement(cell, qn("is"))
    t_node = ET.SubElement(is_node, qn("t"))
    t_node.text = value if value is not None else ""


def set_number_cell(cell: ET.Element, value: str) -> None:
    cell.attrib.pop("t", None)
    for child in list(cell):
        cell.remove(child)
    v_node = ET.SubElement(cell, qn("v"))
    v_node.text = value if value is not None else "0"


def set_formula_cell(cell: ET.Element, formula: str) -> None:
    cell.attrib.pop("t", None)
    for child in list(cell):
        cell.remove(child)
    f_node = ET.SubElement(cell, qn("f"))
    f_node.text = formula


def parse_shared_strings(entries: Dict[str, bytes]) -> List[str]:
    data = entries.get("xl/sharedStrings.xml")
    if not data:
        return []
    root = parse_xml(data)
    out: List[str] = []
    for si in root.findall(qn("si")):
        t = si.find(qn("t"))
        if t is not None and t.text is not None:
            out.append(t.text)
            continue
        # Rich text run support
        parts: List[str] = []
        for r in si.findall(qn("r")):
            rt = r.find(qn("t"))
            if rt is not None and rt.text is not None:
                parts.append(rt.text)
        out.append("".join(parts))
    return out


def get_cell_value(cell: ET.Element, shared_strings: List[str]) -> str:
    t = cell.attrib.get("t")
    if t == "inlineStr":
        txt = cell.find(f"{qn('is')}/{qn('t')}")
        return "" if txt is None or txt.text is None else txt.text
    if t == "s":
        v = cell.find(qn("v"))
        if v is None or v.text is None:
            return ""
        try:
            idx = int(v.text)
        except ValueError:
            return ""
        return shared_strings[idx] if 0 <= idx < len(shared_strings) else ""
    v = cell.find(qn("v"))
    return "" if v is None or v.text is None else v.text


def find_or_create_sheet_data(sheet: ET.Element) -> ET.Element:
    sheet_data = sheet.find(qn("sheetData"))
    if sheet_data is None:
        sheet_data = ET.SubElement(sheet, qn("sheetData"))
    return sheet_data


def sorted_rows(sheet_data: ET.Element) -> List[ET.Element]:
    rows = list(sheet_data.findall(qn("row")))
    rows.sort(key=lambda r: int(r.attrib.get("r", "0") or "0"))
    return rows


def header_map(row: ET.Element, shared_strings: List[str]) -> Dict[str, int]:
    mapping: Dict[str, int] = {}
    for cell in row.findall(qn("c")):
        ref = cell.attrib.get("r", "")
        idx = col_index(ref)
        mapping[get_cell_value(cell, shared_strings).strip()] = idx
    return mapping


def update_dimension(sheet: ET.Element, max_col: int, max_row: int) -> None:
    dim = sheet.find(qn("dimension"))
    ref = f"A1:{col_letter(max(1, max_col))}{max(1, max_row)}"
    if dim is None:
        dim = ET.Element(qn("dimension"), {"ref": ref})
        sheet.insert(0, dim)
    else:
        dim.attrib["ref"] = ref


def extract_dimension_ref(sheet: ET.Element) -> Optional[str]:
    dim = sheet.find(qn("dimension"))
    if dim is None:
        return None
    return dim.attrib.get("ref")


def detect_tag_prefix(raw_xml: str, local_name: str) -> str:
    m = re.search(rf"<([A-Za-z_][\\w\\.-]*:)?{re.escape(local_name)}\\b", raw_xml)
    if not m:
        return ""
    return m.group(1) or ""


def remap_core_tag_prefixes(xml_fragment: str, prefix: str) -> str:
    tags = ["row", "c", "is", "t", "v"]
    out = xml_fragment
    for tag in tags:
        out = re.sub(rf"<(?:[A-Za-z_][\w\.-]*:)?{tag}\b", f"<{prefix}{tag}", out)
        out = re.sub(rf"</(?:[A-Za-z_][\w\.-]*:)?{tag}>", f"</{prefix}{tag}>", out)
    return out


def remap_attribute_prefixes(xml_fragment: str, original_raw_xml: str) -> str:
    attr_prefix_by_local: Dict[str, str] = {}
    for m in re.finditer(r'\b([A-Za-z_][\w\.-]*):([A-Za-z_][\w\.-]*)=', original_raw_xml):
        pref = m.group(1)
        local = m.group(2)
        if pref == "xmlns":
            continue
        attr_prefix_by_local.setdefault(local, pref)

    def repl(match: re.Match) -> str:
        local = match.group(1)
        pref = attr_prefix_by_local.get(local)
        if not pref:
            return match.group(0)
        return f"{pref}:{local}="

    return re.sub(r'\bns\d+:([A-Za-z_][\w\.-]*)=', repl, xml_fragment)


def sheetdata_inner_xml(updated_sheet: ET.Element, original_raw_xml: str) -> str:
    sheet_data = updated_sheet.find(qn("sheetData"))
    if sheet_data is None:
        return ""
    serialized = ET.tostring(sheet_data, encoding="unicode")
    inner_match = re.match(r"^<[^>]+>(.*)</[^>]+>$", serialized, flags=re.DOTALL)
    inner = inner_match.group(1) if inner_match else ""
    target_prefix = detect_tag_prefix(original_raw_xml, "row")
    out = remap_core_tag_prefixes(inner, target_prefix)
    out = remap_attribute_prefixes(out, original_raw_xml)
    return out


def patch_sheet_xml_preserving_metadata(original_raw_xml: str,
                                        updated_sheet: ET.Element) -> str:
    updated_ref = extract_dimension_ref(updated_sheet)
    updated_inner = sheetdata_inner_xml(updated_sheet, original_raw_xml)

    patched = original_raw_xml
    if updated_ref:
        patched = re.sub(
            r'(<(?:[A-Za-z_][\w\.-]*:)?dimension\b[^>]*\bref=")([^"]*)(")',
            lambda m: f"{m.group(1)}{updated_ref}{m.group(3)}",
            patched,
            count=1
        )
    patched = re.sub(
        r'(<(?:[A-Za-z_][\w\.-]*:)?sheetData\b[^>]*>)(.*?)(</(?:[A-Za-z_][\w\.-]*:)?sheetData>)',
        lambda m: f"{m.group(1)}{updated_inner}{m.group(3)}",
        patched,
        count=1,
        flags=re.DOTALL
    )
    return patched


def ensure_header_row(sheet_data: ET.Element, headers: List[str]) -> ET.Element:
    rows = sorted_rows(sheet_data)
    if rows:
        first = rows[0]
        if int(first.attrib.get("r", "1")) != 1:
            first.attrib["r"] = "1"
        return first
    row = ET.Element(qn("row"), {"r": "1"})
    sheet_data.append(row)
    for i, h in enumerate(headers, start=1):
        c = ensure_cell(row, i, 1)
        set_inline_cell(c, h)
    return row


def row_key(row: ET.Element, cols: Dict[str, int], keys: Tuple[str, str], shared_strings: List[str]) -> Tuple[str, str]:
    values: Dict[str, str] = {}
    for name in keys:
        cidx = cols.get(name)
        if not cidx:
            values[name] = ""
            continue
        ref = f"{col_letter(cidx)}{row.attrib.get('r', '0')}"
        cell = None
        for c in row.findall(qn("c")):
            if c.attrib.get("r") == ref:
                cell = c
                break
        values[name] = "" if cell is None else get_cell_value(cell, shared_strings).strip()
    return values[keys[0]], values[keys[1]]


def update_current_progress(sheet_root: ET.Element, csv_rows: List[Dict[str, str]], shared_strings: List[str]) -> None:
    required_cols = ["workstream", "task", "priority", "status", "percent_complete", "last_updated", "notes"]
    sheet_data = find_or_create_sheet_data(sheet_root)
    header = ensure_header_row(sheet_data, required_cols)
    for i, name in enumerate(required_cols, start=1):
        c = ensure_cell(header, i, 1)
        set_inline_cell(c, name)
    cols = {name: i for i, name in enumerate(required_cols, start=1)}

    rows = sorted_rows(sheet_data)
    by_key: Dict[Tuple[str, str], ET.Element] = {}
    max_row = 1
    for row in rows[1:]:
        ridx = int(row.attrib.get("r", "0") or "0")
        max_row = max(max_row, ridx)
        k = row_key(row, cols, ("workstream", "task"), shared_strings)
        if k[0] and k[1]:
            by_key[k] = row

    for item in csv_rows:
        key = (item.get("workstream", "").strip(), item.get("task", "").strip())
        if not key[0] or not key[1]:
            continue
        row = by_key.get(key)
        if row is None:
            max_row += 1
            row = ET.Element(qn("row"), {"r": str(max_row)})
            sheet_data.append(row)
            by_key[key] = row
        ridx = int(row.attrib.get("r", "0") or "0")
        for name in required_cols:
            cidx = cols[name]
            cell = ensure_cell(row, cidx, ridx)
            set_inline_cell(cell, item.get(name, ""))

    update_dimension(sheet_root, max(cols.values()), max_row)


def build_last_history(rows: List[ET.Element],
                       cols: Dict[str, int],
                       shared_strings: List[str]) -> Dict[Tuple[str, str], Dict[str, str]]:
    last: Dict[Tuple[str, str], Dict[str, str]] = {}
    for row in rows:
        ridx = int(row.attrib.get("r", "0") or "0")
        def val(name: str) -> str:
            cidx = cols.get(name)
            if not cidx:
                return ""
            ref = f"{col_letter(cidx)}{ridx}"
            for c in row.findall(qn("c")):
                if c.attrib.get("r") == ref:
                    return get_cell_value(c, shared_strings).strip()
            return ""
        workstream = val("workstream")
        task = val("task")
        if not workstream or not task:
            continue
        key = (workstream, task)
        last[key] = {
            "new_status": val("new_status"),
            "new_percent": val("new_percent"),
            "new_priority": val("new_priority"),
            "csv_last_updated": val("csv_last_updated"),
            "notes": val("notes"),
        }
    return last


def update_status_history(sheet_root: ET.Element, csv_rows: List[Dict[str, str]], shared_strings: List[str]) -> None:
    headers = [
        "updated_at",
        "workstream",
        "task",
        "previous_status",
        "new_status",
        "previous_priority",
        "new_priority",
        "previous_percent",
        "new_percent",
        "csv_last_updated",
        "notes",
    ]
    sheet_data = find_or_create_sheet_data(sheet_root)
    header = ensure_header_row(sheet_data, headers)
    for i, name in enumerate(headers, start=1):
        c = ensure_cell(header, i, 1)
        set_inline_cell(c, name)
    cols = {name: i for i, name in enumerate(headers, start=1)}

    rows = sorted_rows(sheet_data)
    data_rows = rows[1:]
    max_row = max((int(r.attrib.get("r", "0") or "0") for r in rows), default=1)
    last = build_last_history(data_rows, cols, shared_strings)
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()

    for item in csv_rows:
        workstream = item.get("workstream", "").strip()
        task = item.get("task", "").strip()
        status = item.get("status", "").strip()
        priority = item.get("priority", "").strip()
        percent = item.get("percent_complete", "").strip()
        csv_last_updated = item.get("last_updated", "").strip()
        notes = item.get("notes", "").strip()
        if not workstream or not task:
            continue
        key = (workstream, task)
        prev = last.get(key, {})
        prev_status = prev.get("new_status", "")
        prev_priority = prev.get("new_priority", "")
        prev_percent = prev.get("new_percent", "")
        prev_csv_last = prev.get("csv_last_updated", "")
        prev_notes = prev.get("notes", "")
        should_append = (
            not prev
            or prev_status != status
            or prev_priority != priority
            or prev_percent != percent
            or prev_csv_last != csv_last_updated
            or prev_notes != notes
        )
        if not should_append:
            continue

        max_row += 1
        row = ET.Element(qn("row"), {"r": str(max_row)})
        sheet_data.append(row)
        values = {
            "updated_at": now,
            "workstream": workstream,
            "task": task,
            "previous_status": prev_status,
            "new_status": status,
            "previous_priority": prev_priority,
            "new_priority": priority,
            "previous_percent": prev_percent,
            "new_percent": percent,
            "csv_last_updated": csv_last_updated,
            "notes": notes,
        }
        for name in headers:
            cidx = cols[name]
            cell = ensure_cell(row, cidx, max_row)
            set_inline_cell(cell, values[name])
        last[key] = {
            "new_status": status,
            "new_percent": percent,
            "new_priority": priority,
            "csv_last_updated": csv_last_updated,
            "notes": notes,
        }

    update_dimension(sheet_root, max(cols.values()), max_row)


def create_empty_workbook(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    workbook = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="{NS_MAIN}" xmlns:r="{NS_REL_DOC}">
  <sheets>
    <sheet name="Current Progress" sheetId="1" r:id="rId1"/>
    <sheet name="Status History" sheetId="2" r:id="rId2"/>
    <sheet name="Completion Trend" sheetId="3" r:id="rId3"/>
  </sheets>
</workbook>
"""
    workbook_rels = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="{NS_REL_PKG}">
  <Relationship Id="rId1" Type="{NS_REL_DOC}/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="{NS_REL_DOC}/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="{NS_REL_DOC}/worksheet" Target="worksheets/sheet3.xml"/>
</Relationships>
"""
    content_types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>
"""
    root_rels = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="{NS_REL_PKG}">
  <Relationship Id="rId1" Type="{NS_REL_DOC}/officeDocument" Target="xl/workbook.xml"/>
</Relationships>
"""
    empty_sheet = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="{NS_MAIN}">
  <dimension ref="A1:A1"/>
  <sheetData/>
</worksheet>
"""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", content_types.encode("utf-8"))
        zf.writestr("_rels/.rels", root_rels.encode("utf-8"))
        zf.writestr("xl/workbook.xml", workbook.encode("utf-8"))
        zf.writestr("xl/_rels/workbook.xml.rels", workbook_rels.encode("utf-8"))
        zf.writestr("xl/worksheets/sheet1.xml", empty_sheet.encode("utf-8"))
        zf.writestr("xl/worksheets/sheet2.xml", empty_sheet.encode("utf-8"))
        zf.writestr("xl/worksheets/sheet3.xml", empty_sheet.encode("utf-8"))


def deep_clone(node: ET.Element) -> ET.Element:
    return ET.fromstring(ET.tostring(node, encoding="utf-8"))


def load_entries_from_workbook_or_zip(path: Path) -> Dict[str, bytes]:
    if not path.exists():
        raise SystemExit(f"Missing filter template: {path}")
    if path.suffix.lower() == ".xlsx":
        with zipfile.ZipFile(path, "r") as zf:
            return {name: zf.read(name) for name in zf.namelist()}
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path, "r") as outer:
            xlsx_names = [n for n in outer.namelist() if n.lower().endswith(".xlsx")]
            if not xlsx_names:
                raise SystemExit(f"No .xlsx file found inside template zip: {path}")
            payload = outer.read(xlsx_names[0])
        with zipfile.ZipFile(io.BytesIO(payload), "r") as inner:
            return {name: inner.read(name) for name in inner.namelist()}
    raise SystemExit(f"Unsupported template extension for {path}; expected .xlsx or .zip")


def apply_filter_sort_template(target_sheet: ET.Element,
                               template_sheet: ET.Element,
                               force: bool = False) -> None:
    if template_sheet is None:
        return
    # Preserve user filter/sort setup by replacing top-level worksheet filter/sort nodes from template.
    template_auto = template_sheet.find(qn("autoFilter"))
    template_sort = template_sheet.find(qn("sortState"))
    if template_auto is None and template_sort is None:
        return
    target_has_auto = target_sheet.find(qn("autoFilter")) is not None
    target_has_sort = target_sheet.find(qn("sortState")) is not None
    if not force and (target_has_auto or target_has_sort):
        return

    target_children = list(target_sheet)
    insert_index = len(target_children)
    for i, child in enumerate(target_children):
        if child.tag in {qn("mergeCells"), qn("phoneticPr"), qn("conditionalFormatting"), qn("dataValidations"),
                         qn("hyperlinks"), qn("printOptions"), qn("pageMargins"), qn("pageSetup"),
                         qn("headerFooter"), qn("drawing"), qn("legacyDrawing"), qn("extLst")}:
            insert_index = i
            break

    for child in list(target_sheet):
        if child.tag in {qn("autoFilter"), qn("sortState")}:
            target_sheet.remove(child)

    offset = 0
    if template_auto is not None:
        target_sheet.insert(insert_index + offset, deep_clone(template_auto))
        offset += 1
    if template_sort is not None:
        target_sheet.insert(insert_index + offset, deep_clone(template_sort))


def parse_percent(raw: str) -> Optional[float]:
    text = (raw or "").strip()
    if not text:
        return None
    if text.endswith("%"):
        text = text[:-1].strip()
    try:
        return float(text)
    except ValueError:
        return None


def parse_history_events(history_sheet: ET.Element, shared_strings: List[str]) -> List[Dict[str, str]]:
    sheet_data = history_sheet.find(qn("sheetData"))
    if sheet_data is None:
        return []
    rows = sorted_rows(sheet_data)
    if not rows:
        return []
    cols = header_map(rows[0], shared_strings)
    events: List[Dict[str, str]] = []
    for row in rows[1:]:
        ridx = int(row.attrib.get("r", "0") or "0")

        def val(name: str) -> str:
            cidx = cols.get(name)
            if not cidx:
                return ""
            ref = f"{col_letter(cidx)}{ridx}"
            for c in row.findall(qn("c")):
                if c.attrib.get("r") == ref:
                    return get_cell_value(c, shared_strings).strip()
            return ""

        workstream = val("workstream")
        task = val("task")
        if not workstream or not task:
            continue
        events.append({
            "workstream": workstream,
            "task": task,
            "updated_at": val("updated_at"),
            "new_percent": val("new_percent"),
            "new_priority": val("new_priority"),
        })
    return events


def ensure_content_type_override(entries: Dict[str, bytes], part_name: str, content_type: str) -> None:
    content_types_path = "[Content_Types].xml"
    root = parse_xml(entries[content_types_path])
    part_name_slash = part_name if part_name.startswith("/") else f"/{part_name}"
    for override in root.findall(ct_qn("Override")):
        if override.attrib.get("PartName") == part_name_slash:
            override.attrib["ContentType"] = content_type
            entries[content_types_path] = xml_bytes(root)
            return
    ET.SubElement(root, ct_qn("Override"), {"PartName": part_name_slash, "ContentType": content_type})
    entries[content_types_path] = xml_bytes(root)


def remove_content_type_override(entries: Dict[str, bytes], part_name: str) -> None:
    root = parse_xml(entries["[Content_Types].xml"])
    target = part_name if part_name.startswith("/") else f"/{part_name}"
    changed = False
    for node in list(root.findall(ct_qn("Override"))):
        if node.attrib.get("PartName") == target:
            root.remove(node)
            changed = True
    if changed:
        entries["[Content_Types].xml"] = xml_bytes(root)


def cleanup_completion_trend_chart_parts(entries: Dict[str, bytes], sheet_path: str) -> None:
    sheet_rels_path = posixpath.join(posixpath.dirname(sheet_path), "_rels", f"{posixpath.basename(sheet_path)}.rels")
    drawing_parts: List[str] = []
    chart_parts: List[str] = []
    if sheet_rels_path in entries:
        rels = parse_xml(entries[sheet_rels_path])
        for rel in list(rels.findall(rel_qn("Relationship"))):
            if rel.attrib.get("Type") == f"{NS_REL_DOC}/drawing":
                target = rel.attrib.get("Target", "")
                if target:
                    if target.startswith("/"):
                        drawing_parts.append(target.lstrip("/"))
                    else:
                        drawing_parts.append(posixpath.normpath(posixpath.join(posixpath.dirname(sheet_path), target)))
                rels.remove(rel)
        if len(rels.findall(rel_qn("Relationship"))) == 0:
            entries.pop(sheet_rels_path, None)
        else:
            entries[sheet_rels_path] = xml_bytes(rels)

    sheet_xml = parse_xml(entries[sheet_path])
    for child in list(sheet_xml):
        if child.tag == qn("drawing"):
            sheet_xml.remove(child)
    entries[sheet_path] = xml_bytes(sheet_xml)

    for drawing_part in drawing_parts:
        drawing_rels_path = posixpath.join(posixpath.dirname(drawing_part), "_rels", f"{posixpath.basename(drawing_part)}.rels")
        if drawing_rels_path in entries:
            d_rels = parse_xml(entries[drawing_rels_path])
            for rel in d_rels.findall(rel_qn("Relationship")):
                if rel.attrib.get("Type") == f"{NS_REL_DOC}/chart":
                    target = rel.attrib.get("Target", "")
                    if target:
                        if target.startswith("/"):
                            chart_parts.append(target.lstrip("/"))
                        else:
                            chart_parts.append(posixpath.normpath(posixpath.join(posixpath.dirname(drawing_part), target)))
            entries.pop(drawing_rels_path, None)
        entries.pop(drawing_part, None)
        remove_content_type_override(entries, drawing_part)

    for chart_part in chart_parts:
        entries.pop(chart_part, None)
        remove_content_type_override(entries, chart_part)


def ensure_completion_trend_sheet(entries: Dict[str, bytes]) -> str:
    workbook_path = "xl/workbook.xml"
    rels_path = "xl/_rels/workbook.xml.rels"
    workbook = parse_xml(entries[workbook_path])
    rels = parse_xml(entries[rels_path])

    rel_by_id: Dict[str, str] = {}
    max_rid = 0
    for rel in rels.findall(rel_qn("Relationship")):
        rid = rel.attrib.get("Id", "")
        target = rel.attrib.get("Target", "")
        if rid:
            rel_by_id[rid] = target
            m = re.match(r"rId(\d+)$", rid)
            if m:
                max_rid = max(max_rid, int(m.group(1)))

    sheets = workbook.find(qn("sheets"))
    if sheets is None:
        sheets = ET.SubElement(workbook, qn("sheets"))

    max_sheet_id = 0
    existing_sheet_nums: List[int] = []
    for sheet in sheets.findall(qn("sheet")):
        sid = sheet.attrib.get("sheetId")
        if sid and sid.isdigit():
            max_sheet_id = max(max_sheet_id, int(sid))
        rid = sheet.attrib.get(f"{{{NS_REL_DOC}}}id", "")
        target = rel_by_id.get(rid, "")
        if target:
            m = re.search(r"sheet(\d+)\.xml$", target)
            if m:
                existing_sheet_nums.append(int(m.group(1)))
        if sheet.attrib.get("name") == "Completion Trend":
            target = rel_by_id.get(rid, "")
            if target.startswith("/"):
                sheet_path = target.lstrip("/")
            elif target.startswith("xl/"):
                sheet_path = target
            else:
                sheet_path = posixpath.normpath(posixpath.join("xl", target))
            entries[workbook_path] = xml_bytes(workbook)
            entries[rels_path] = xml_bytes(rels)
            ensure_content_type_override(
                entries,
                sheet_path,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
            )
            return sheet_path

    next_sheet_num = 1
    if existing_sheet_nums:
        next_sheet_num = max(existing_sheet_nums) + 1
    while f"xl/worksheets/sheet{next_sheet_num}.xml" in entries:
        next_sheet_num += 1
    target_rel = f"worksheets/sheet{next_sheet_num}.xml"
    new_rid = f"rId{max_rid + 1}"
    rel = ET.SubElement(rels, rel_qn("Relationship"))
    rel.attrib["Id"] = new_rid
    rel.attrib["Type"] = f"{NS_REL_DOC}/worksheet"
    rel.attrib["Target"] = target_rel

    sheet = ET.SubElement(sheets, qn("sheet"))
    sheet.attrib["name"] = "Completion Trend"
    sheet.attrib["sheetId"] = str(max_sheet_id + 1 if max_sheet_id > 0 else 1)
    sheet.attrib[f"{{{NS_REL_DOC}}}id"] = new_rid

    sheet_path = f"xl/{target_rel}"
    ensure_content_type_override(
        entries,
        sheet_path,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
    )
    entries[workbook_path] = xml_bytes(workbook)
    entries[rels_path] = xml_bytes(rels)
    return sheet_path


def ensure_progress_graph_sheet(entries: Dict[str, bytes]) -> str:
    workbook_path = "xl/workbook.xml"
    rels_path = "xl/_rels/workbook.xml.rels"
    workbook = parse_xml(entries[workbook_path])
    rels = parse_xml(entries[rels_path])

    rel_by_id: Dict[str, str] = {}
    max_rid = 0
    for rel in rels.findall(rel_qn("Relationship")):
        rid = rel.attrib.get("Id", "")
        target = rel.attrib.get("Target", "")
        if rid:
            rel_by_id[rid] = target
            m = re.match(r"rId(\d+)$", rid)
            if m:
                max_rid = max(max_rid, int(m.group(1)))

    sheets = workbook.find(qn("sheets"))
    if sheets is None:
        sheets = ET.SubElement(workbook, qn("sheets"))

    max_sheet_id = 0
    existing_sheet_nums: List[int] = []
    for sheet in sheets.findall(qn("sheet")):
        sid = sheet.attrib.get("sheetId")
        if sid and sid.isdigit():
            max_sheet_id = max(max_sheet_id, int(sid))
        rid = sheet.attrib.get(f"{{{NS_REL_DOC}}}id", "")
        target = rel_by_id.get(rid, "")
        if target:
            m = re.search(r"sheet(\d+)\.xml$", target)
            if m:
                existing_sheet_nums.append(int(m.group(1)))
        if sheet.attrib.get("name") == "Progress Graph":
            target = rel_by_id.get(rid, "")
            if target.startswith("/"):
                sheet_path = target.lstrip("/")
            elif target.startswith("xl/"):
                sheet_path = target
            else:
                sheet_path = posixpath.normpath(posixpath.join("xl", target))
            entries[workbook_path] = xml_bytes(workbook)
            entries[rels_path] = xml_bytes(rels)
            ensure_content_type_override(
                entries,
                sheet_path,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
            )
            return sheet_path

    next_sheet_num = 1
    if existing_sheet_nums:
        next_sheet_num = max(existing_sheet_nums) + 1
    while f"xl/worksheets/sheet{next_sheet_num}.xml" in entries:
        next_sheet_num += 1
    target_rel = f"worksheets/sheet{next_sheet_num}.xml"
    new_rid = f"rId{max_rid + 1}"
    rel = ET.SubElement(rels, rel_qn("Relationship"))
    rel.attrib["Id"] = new_rid
    rel.attrib["Type"] = f"{NS_REL_DOC}/worksheet"
    rel.attrib["Target"] = target_rel

    sheet = ET.SubElement(sheets, qn("sheet"))
    sheet.attrib["name"] = "Progress Graph"
    sheet.attrib["sheetId"] = str(max_sheet_id + 1 if max_sheet_id > 0 else 1)
    sheet.attrib[f"{{{NS_REL_DOC}}}id"] = new_rid

    sheet_path = f"xl/{target_rel}"
    ensure_content_type_override(
        entries,
        sheet_path,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
    )
    entries[workbook_path] = xml_bytes(workbook)
    entries[rels_path] = xml_bytes(rels)
    return sheet_path


def sync_extended_properties_sheet_list(entries: Dict[str, bytes]) -> None:
    workbook_path = "xl/workbook.xml"
    app_props_path = "docProps/app.xml"
    if workbook_path not in entries or app_props_path not in entries:
        return

    workbook = parse_xml(entries[workbook_path])
    app = parse_xml(entries[app_props_path])
    sheets = workbook.find(qn("sheets"))
    sheet_names: List[str] = []
    if sheets is not None:
        for sheet in sheets.findall(qn("sheet")):
            name = sheet.attrib.get("name")
            if name:
                sheet_names.append(name)

    heading_pairs = app.find(app_qn("HeadingPairs"))
    if heading_pairs is not None:
        vector = heading_pairs.find(vt_qn("vector"))
        if vector is not None:
            variants = vector.findall(vt_qn("variant"))
            if len(variants) >= 2:
                count_node = variants[1].find(vt_qn("i4"))
                if count_node is None:
                    count_node = ET.SubElement(variants[1], vt_qn("i4"))
                count_node.text = str(len(sheet_names))
                vector.attrib["size"] = "2"

    titles_of_parts = app.find(app_qn("TitlesOfParts"))
    if titles_of_parts is not None:
        vector = titles_of_parts.find(vt_qn("vector"))
        if vector is None:
            vector = ET.SubElement(titles_of_parts, vt_qn("vector"))
            vector.attrib["baseType"] = "lpstr"
        for child in list(vector):
            vector.remove(child)
        for name in sheet_names:
            node = ET.SubElement(vector, vt_qn("lpstr"))
            node.text = name
        vector.attrib["size"] = str(len(sheet_names))

    entries[app_props_path] = xml_bytes(app)


def build_ascii_trend(values: List[float]) -> str:
    if not values:
        return ""
    chars = " .:-=+*#%@"
    out: List[str] = []
    for val in values:
        clamped = max(0.0, min(100.0, val))
        idx = int(round((len(chars) - 1) * clamped / 100.0))
        out.append(chars[idx])
    return "".join(out)


def build_completion_trend_rows(csv_rows: List[Dict[str, str]],
                                events: List[Dict[str, str]]) -> List[Dict[str, str]]:
    csv_by_key: Dict[Tuple[str, str], Dict[str, str]] = {}
    ordered_keys: List[Tuple[str, str]] = []
    for row in csv_rows:
        key = (row.get("workstream", "").strip(), row.get("task", "").strip())
        if not key[0] or not key[1]:
            continue
        csv_by_key[key] = row
        ordered_keys.append(key)

    timelines: Dict[Tuple[str, str], List[Dict[str, str]]] = {}
    for event in events:
        key = (event.get("workstream", "").strip(), event.get("task", "").strip())
        if not key[0] or not key[1]:
            continue
        if key not in csv_by_key:
            # Ignore stale/unknown historical keys so trend data stays aligned with the active plan.
            continue
        timelines.setdefault(key, []).append(event)

    out: List[Dict[str, str]] = []
    for key in ordered_keys:
        workstream, task = key
        csv_row = csv_by_key.get(key, {})
        priority = (csv_row.get("priority", "") or "").strip()
        history = timelines.get(key, [])
        if not history:
            current_percent = parse_percent(csv_row.get("percent_complete", "") or "")
            if current_percent is None:
                current_percent = 0.0
            points = [current_percent]
            out.append({
                "workstream": workstream,
                "task": task,
                "priority": priority,
                "iteration": "1",
                "updated_at": (csv_row.get("last_updated", "") or "").strip(),
                "percent_complete": f"{current_percent:.2f}".rstrip("0").rstrip("."),
                "delta_percent": "",
                "trend_points": f"{current_percent:.2f}".rstrip("0").rstrip("."),
                "trend_line_ascii": build_ascii_trend(points),
            })
            continue

        points: List[float] = []
        prev: Optional[float] = None
        for idx, item in enumerate(history, start=1):
            point = parse_percent(item.get("new_percent", ""))
            if point is None:
                point = prev if prev is not None else 0.0
            points.append(point)
            delta = "" if prev is None else f"{point - prev:+.2f}".rstrip("0").rstrip(".")
            percent_text = f"{point:.2f}".rstrip("0").rstrip(".")
            points_text = ",".join(f"{p:.2f}".rstrip("0").rstrip(".") for p in points)
            out.append({
                "workstream": workstream,
                "task": task,
                "priority": priority or (item.get("new_priority", "") or "").strip(),
                "iteration": str(idx),
                "updated_at": (item.get("updated_at", "") or "").strip(),
                "percent_complete": percent_text,
                "delta_percent": delta,
                "trend_points": points_text,
                "trend_line_ascii": build_ascii_trend(points),
            })
            prev = point
    return out


def update_completion_trend(sheet_root: ET.Element,
                            csv_rows: List[Dict[str, str]],
                            history_sheet: ET.Element,
                            shared_strings: List[str]) -> None:
    headers = [
        "workstream",
        "task",
        "priority",
        "iteration",
        "updated_at",
        "percent_complete",
        "delta_percent",
        "trend_points",
        "trend_line_ascii",
        "trend_formula_graph",
    ]
    events = parse_history_events(history_sheet, shared_strings)
    rows_data = build_completion_trend_rows(csv_rows, events)
    sheet_data = find_or_create_sheet_data(sheet_root)
    for row in list(sheet_data.findall(qn("row"))):
        sheet_data.remove(row)

    header_row = ET.Element(qn("row"), {"r": "1"})
    sheet_data.append(header_row)
    for i, name in enumerate(headers, start=1):
        c = ensure_cell(header_row, i, 1)
        set_inline_cell(c, name)

    max_row = 1
    for item in rows_data:
        max_row += 1
        row = ET.Element(qn("row"), {"r": str(max_row)})
        sheet_data.append(row)
        for cidx, name in enumerate(headers, start=1):
            c = ensure_cell(row, cidx, max_row)
            if name == "trend_formula_graph":
                set_formula_cell(c, f'REPT("|",ROUND(F{max_row}/5,0))')
            else:
                set_inline_cell(c, item.get(name, ""))
    update_dimension(sheet_root, len(headers), max_row)


def row_cell_value(row: ET.Element, cidx: int, shared_strings: List[str]) -> str:
    for cell in row.findall(qn("c")):
        ref = cell.attrib.get("r", "")
        if col_index(ref) == cidx:
            return get_cell_value(cell, shared_strings)
    return ""


def parse_trend_points(trend_sheet: ET.Element, shared_strings: List[str]) -> List[Dict[str, str]]:
    sheet_data = find_or_create_sheet_data(trend_sheet)
    rows = sorted_rows(sheet_data)
    if not rows:
        return []
    headers = header_map(rows[0], shared_strings)
    out: List[Dict[str, str]] = []
    for row in rows[1:]:
        workstream = row_cell_value(row, headers.get("workstream", 0), shared_strings).strip()
        task = row_cell_value(row, headers.get("task", 0), shared_strings).strip()
        iteration = row_cell_value(row, headers.get("iteration", 0), shared_strings).strip()
        percent = row_cell_value(row, headers.get("percent_complete", 0), shared_strings).strip()
        if not workstream or not task:
            continue
        out.append({
            "workstream": workstream,
            "task": task,
            "iteration": iteration,
            "percent_complete": percent,
        })
    return out


def update_progress_graph(sheet_root: ET.Element,
                          trend_sheet: ET.Element,
                          shared_strings: List[str]) -> None:
    points = parse_trend_points(trend_sheet, shared_strings)
    task_order: List[str] = []
    percent_by_task_iter: Dict[Tuple[str, int], float] = {}
    max_iteration = 0

    for p in points:
        label = f"{p.get('workstream', '').strip()} | {p.get('task', '').strip()}"
        if label not in task_order:
            task_order.append(label)
        try:
            iteration = int(float((p.get("iteration", "") or "0").strip() or "0"))
        except ValueError:
            iteration = 0
        iteration = max(1, iteration)
        max_iteration = max(max_iteration, iteration)
        percent = parse_percent(p.get("percent_complete", "") or "")
        if percent is None:
            continue
        percent_by_task_iter[(label, iteration)] = percent

    sheet_data = find_or_create_sheet_data(sheet_root)
    for row in list(sheet_data.findall(qn("row"))):
        sheet_data.remove(row)

    if not task_order:
        header = ET.Element(qn("row"), {"r": "1"})
        sheet_data.append(header)
        set_inline_cell(ensure_cell(header, 1, 1), "iteration")
        row2 = ET.Element(qn("row"), {"r": "2"})
        sheet_data.append(row2)
        set_inline_cell(ensure_cell(row2, 1, 2), "No trend data available yet.")
        update_dimension(sheet_root, 1, 2)
        return

    max_iteration = max(1, max_iteration)
    header = ET.Element(qn("row"), {"r": "1"})
    sheet_data.append(header)
    set_inline_cell(ensure_cell(header, 1, 1), "iteration")
    for i, label in enumerate(task_order, start=2):
        set_inline_cell(ensure_cell(header, i, 1), label)

    row_no = 1
    for iteration in range(1, max_iteration + 1):
        row_no += 1
        row = ET.Element(qn("row"), {"r": str(row_no)})
        sheet_data.append(row)
        set_number_cell(ensure_cell(row, 1, row_no), str(iteration))
        for col, label in enumerate(task_order, start=2):
            val = percent_by_task_iter.get((label, iteration))
            if val is None:
                set_inline_cell(ensure_cell(row, col, row_no), "")
            else:
                set_number_cell(ensure_cell(row, col, row_no), f"{val:.2f}".rstrip("0").rstrip("."))

    row_no += 2
    summary_header = ET.Element(qn("row"), {"r": str(row_no)})
    sheet_data.append(summary_header)
    set_inline_cell(ensure_cell(summary_header, 1, row_no), "task")
    set_inline_cell(ensure_cell(summary_header, 2, row_no), "trend_line_ascii")
    set_inline_cell(ensure_cell(summary_header, 3, row_no), "latest_percent")

    for label in task_order:
        row_no += 1
        row = ET.Element(qn("row"), {"r": str(row_no)})
        sheet_data.append(row)
        set_inline_cell(ensure_cell(row, 1, row_no), label)
        series: List[float] = []
        last_val = 0.0
        for iteration in range(1, max_iteration + 1):
            val = percent_by_task_iter.get((label, iteration))
            if val is None:
                val = last_val
            series.append(val)
            last_val = val
        latest = series[-1] if series else 0.0
        set_inline_cell(ensure_cell(row, 2, row_no), build_ascii_trend(series))
        set_number_cell(ensure_cell(row, 3, row_no), f"{latest:.2f}".rstrip("0").rstrip("."))

    max_col = max(3, len(task_order) + 1)
    update_dimension(sheet_root, max_col, row_no)


def update_workbook_in_place(xlsx_path: Path,
                             csv_rows: List[Dict[str, str]],
                             filter_template: Optional[Path],
                             force_filter_template: bool) -> None:
    if not xlsx_path.exists():
        create_empty_workbook(xlsx_path)

    with zipfile.ZipFile(xlsx_path, "r") as zin:
        entries = {name: zin.read(name) for name in zin.namelist()}
    shared_strings = parse_shared_strings(entries)

    with zipfile.ZipFile(xlsx_path, "r") as zf:
        current_path = sheet_path_by_name(zf, "Current Progress")
        history_path = sheet_path_by_name(zf, "Status History")

    if not current_path or not history_path:
        create_empty_workbook(xlsx_path)
        with zipfile.ZipFile(xlsx_path, "r") as zf:
            entries = {name: zf.read(name) for name in zf.namelist()}
            current_path = sheet_path_by_name(zf, "Current Progress")
            history_path = sheet_path_by_name(zf, "Status History")
    assert current_path and history_path

    trend_path = ensure_completion_trend_sheet(entries)
    graph_path = ensure_progress_graph_sheet(entries)
    trend_before = entries.get(trend_path, f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="{NS_MAIN}">
  <dimension ref="A1:A1"/>
  <sheetData/>
</worksheet>
""".encode("utf-8"))
    graph_before = entries.get(graph_path, f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="{NS_MAIN}">
  <dimension ref="A1:A1"/>
  <sheetData/>
</worksheet>
""".encode("utf-8"))

    current_before = entries[current_path]
    history_before = entries[history_path]
    current_xml = parse_xml(current_before)
    history_xml = parse_xml(history_before)
    trend_xml = parse_xml(trend_before)
    graph_xml = parse_xml(graph_before)

    update_current_progress(current_xml, csv_rows, shared_strings)
    update_status_history(history_xml, csv_rows, shared_strings)
    update_completion_trend(trend_xml, csv_rows, history_xml, shared_strings)
    update_progress_graph(graph_xml, trend_xml, shared_strings)

    if filter_template is not None:
        template_entries = load_entries_from_workbook_or_zip(filter_template)
        template_current = None
        template_history = None
        # Template lookup by known sheet paths first, then by workbook sheet names.
        if "xl/worksheets/sheet1.xml" in template_entries:
            template_current = parse_xml(template_entries["xl/worksheets/sheet1.xml"])
        if "xl/worksheets/sheet2.xml" in template_entries:
            template_history = parse_xml(template_entries["xl/worksheets/sheet2.xml"])
        if template_current is not None:
            apply_filter_sort_template(current_xml, template_current, force_filter_template)
        if template_history is not None:
            apply_filter_sort_template(history_xml, template_history, force_filter_template)

    current_after_text = patch_sheet_xml_preserving_metadata(current_before.decode("utf-8"), current_xml)
    history_after_text = patch_sheet_xml_preserving_metadata(history_before.decode("utf-8"), history_xml)
    current_after = current_after_text.encode("utf-8")
    history_after = history_after_text.encode("utf-8")
    trend_after = xml_bytes(trend_xml)
    if (b"autoFilter" in current_before) and (b"autoFilter" not in current_after):
        raise SystemExit("Refusing workbook update: autoFilter would be removed from Current Progress sheet.")
    if (b"autoFilter" in history_before) and (b"autoFilter" not in history_after):
        raise SystemExit("Refusing workbook update: autoFilter would be removed from Status History sheet.")

    entries[current_path] = current_after
    entries[history_path] = history_after
    entries[trend_path] = trend_after
    entries[graph_path] = xml_bytes(graph_xml)
    sync_extended_properties_sheet_list(entries)
    cleanup_completion_trend_chart_parts(entries, trend_path)

    tmp = xlsx_path.with_suffix(xlsx_path.suffix + ".tmp")
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED) as zout:
        for name, data in entries.items():
            zout.writestr(name, data)
    tmp.replace(xlsx_path)


def main() -> int:
    parser = argparse.ArgumentParser(description="Safely update project plan workbook in-place while preserving sheet metadata (filters/layout).")
    parser.add_argument("--csv", required=True, help="Path to source CSV file")
    parser.add_argument("--xlsx", required=True, help="Path to destination XLSX file")
    parser.add_argument(
        "--filter-template",
        default="",
        help="Optional .xlsx or .zip source whose filter/sort nodes are applied after updates"
    )
    parser.add_argument(
        "--force-filter-template",
        action="store_true",
        help="Force replacing target sheet filter/sort nodes from template even when target already has them"
    )
    parser.add_argument(
        "--human-approved",
        default="",
        help="Required token to allow overwriting docs/project-plan-progress.xlsx"
    )
    args = parser.parse_args()

    csv_path = Path(args.csv)
    xlsx_path = Path(args.xlsx)
    if not csv_path.exists():
        raise SystemExit(f"Missing CSV: {csv_path}")
    if xlsx_path.as_posix().endswith("docs/project-plan-progress.xlsx"):
        if args.human_approved != "I_HAVE_EXPLICIT_HUMAN_APPROVAL":
            raise SystemExit(
                "Refusing to overwrite docs/project-plan-progress.xlsx without "
                "--human-approved I_HAVE_EXPLICIT_HUMAN_APPROVAL"
            )
    rows = load_csv_rows(csv_path)
    template = Path(args.filter_template) if args.filter_template else None
    update_workbook_in_place(xlsx_path, rows, template, args.force_filter_template)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
