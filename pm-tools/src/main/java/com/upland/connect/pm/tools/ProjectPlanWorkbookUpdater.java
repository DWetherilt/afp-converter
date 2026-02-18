package com.upland.connect.pm.tools;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.officeDocument.x2006.docPropsVTypes.CTVector;
import org.openxmlformats.schemas.officeDocument.x2006.docPropsVTypes.CTVariant;
import org.openxmlformats.schemas.officeDocument.x2006.extendedProperties.CTProperties;
import org.openxmlformats.schemas.officeDocument.x2006.extendedProperties.CTVectorLpstr;
import org.openxmlformats.schemas.officeDocument.x2006.extendedProperties.CTVectorVariant;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ProjectPlanWorkbookUpdater {

    private static final String SHEET_CURRENT = "Current Progress";
    private static final String SHEET_HISTORY = "Status History";
    private static final String SHEET_TREND = "Completion Trend";
    private static final String SHEET_GRAPH = "Progress Graph";

    private static final List<String> REQUIRED_COLUMNS = List.of(
        "workstream", "task", "priority", "status", "percent_complete", "last_updated", "notes"
    );

    private ProjectPlanWorkbookUpdater() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path csvPath = requiredPath(cli, "--csv");
        Path xlsxPath = requiredPath(cli, "--xlsx");
        Path templatePath = optionalPath(cli, "--template");

        List<Map<String, String>> rows = readCsv(csvPath);
        prepareWorkbookPath(xlsxPath, templatePath);

        try (Workbook workbook = openWorkbook(xlsxPath)) {
            Sheet current = ensureSheet(workbook, SHEET_CURRENT);
            Sheet history = ensureSheet(workbook, SHEET_HISTORY);
            Sheet trend = ensureSheet(workbook, SHEET_TREND);
            Sheet graph = ensureSheet(workbook, SHEET_GRAPH);

            updateCurrent(current, rows);
            appendStatusHistory(history, rows);
            rebuildTrend(trend, history);
            rebuildGraph(graph, trend);
            syncExtendedProperties(workbook);

            try (OutputStream os = Files.newOutputStream(xlsxPath)) {
                workbook.write(os);
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) {
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + k);
            }
            out.put(k, args[++i]);
        }
        return out;
    }

    private static Path requiredPath(Map<String, String> cli, String key) {
        String v = cli.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return Path.of(v);
    }

    private static Path optionalPath(Map<String, String> cli, String key) {
        String v = cli.get(key);
        return (v == null || v.isBlank()) ? null : Path.of(v);
    }

    private static List<Map<String, String>> readCsv(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            throw new IllegalArgumentException("Missing CSV: " + csvPath);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .build();
        try (CSVParser parser = CSVParser.parse(csvPath, StandardCharsets.UTF_8, format)) {
            List<String> headers = parser.getHeaderNames();
            for (String required : REQUIRED_COLUMNS) {
                if (!headers.contains(required)) {
                    throw new IllegalArgumentException("CSV missing required column: " + required);
                }
            }
            List<Map<String, String>> out = new ArrayList<>();
            for (CSVRecord r : parser) {
                Map<String, String> row = new HashMap<>();
                for (String c : REQUIRED_COLUMNS) {
                    row.put(c, Optional.ofNullable(r.get(c)).orElse("").trim());
                }
                out.add(row);
            }
            return out;
        }
    }

    private static void prepareWorkbookPath(Path xlsxPath, Path templatePath) throws IOException {
        if (Files.exists(xlsxPath)) {
            return;
        }
        Files.createDirectories(xlsxPath.toAbsolutePath().getParent());
        if (templatePath != null && Files.exists(templatePath)) {
            if (templatePath.toString().toLowerCase().endsWith(".zip")) {
                try (ZipFile zf = new ZipFile(templatePath.toFile())) {
                    ZipEntry target = zf.getEntry("project-plan-progress.xlsx");
                    if (target != null) {
                        try (InputStream in = zf.getInputStream(target)) {
                            Files.copy(in, xlsxPath);
                            return;
                        }
                    }
                }
            } else {
                Files.copy(templatePath, xlsxPath);
                return;
            }
        }
        try (Workbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(xlsxPath)) {
            wb.createSheet(SHEET_CURRENT);
            wb.createSheet(SHEET_HISTORY);
            wb.createSheet(SHEET_TREND);
            wb.createSheet(SHEET_GRAPH);
            wb.write(os);
        }
    }

    private static Workbook openWorkbook(Path xlsxPath) throws IOException {
        try (InputStream is = Files.newInputStream(xlsxPath)) {
            return new XSSFWorkbook(is);
        }
    }

    private static Sheet ensureSheet(Workbook workbook, String name) {
        Sheet s = workbook.getSheet(name);
        if (s != null) {
            return s;
        }
        return workbook.createSheet(name);
    }

    private static void clearSheet(Sheet sheet) {
        for (int i = sheet.getLastRowNum(); i >= 0; i--) {
            Row row = sheet.getRow(i);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static void setCell(Row row, int idx, String value) {
        Cell c = row.getCell(idx);
        if (c == null) {
            c = row.createCell(idx);
        }
        c.setCellValue(value == null ? "" : value);
    }

    private static String getCell(Row row, int idx) {
        if (row == null) {
            return "";
        }
        Cell c = row.getCell(idx);
        if (c == null) {
            return "";
        }
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> Double.toString(c.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(c.getBooleanCellValue());
            case FORMULA -> c.getCellFormula();
            default -> "";
        };
    }

    private static void updateCurrent(Sheet current, List<Map<String, String>> rows) {
        clearSheet(current);
        String[] headers = {"workstream", "task", "priority", "status", "percent_complete", "last_updated", "notes"};
        Row hr = current.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            setCell(hr, i, headers[i]);
        }
        int rowNo = 1;
        for (Map<String, String> src : rows) {
            Row row = current.createRow(rowNo++);
            setCell(row, 0, src.get("workstream"));
            setCell(row, 1, src.get("task"));
            setCell(row, 2, src.get("priority"));
            setCell(row, 3, src.get("status"));
            setCell(row, 4, src.get("percent_complete"));
            setCell(row, 5, src.get("last_updated"));
            setCell(row, 6, src.get("notes"));
        }
        if (rowNo < 2) {
            current.createRow(1);
            rowNo = 2;
        }
        current.setAutoFilter(new CellRangeAddress(0, rowNo - 1, 0, 6));
    }

    private static void ensureHistoryHeader(Sheet history) {
        if (history.getRow(0) == null) {
            history.createRow(0);
        }
        String[] headers = {
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
            "notes"
        };
        Row hr = history.getRow(0);
        for (int i = 0; i < headers.length; i++) {
            setCell(hr, i, headers[i]);
        }
    }

    private record HistState(String status, String priority, String percent, String csvLast, String notes) {}

    private static void appendStatusHistory(Sheet history, List<Map<String, String>> currentRows) {
        ensureHistoryHeader(history);
        Map<String, Integer> lastByKey = new HashMap<>();
        for (int r = 1; r <= history.getLastRowNum(); r++) {
            Row row = history.getRow(r);
            String ws = getCell(row, 1).trim();
            String task = getCell(row, 2).trim();
            if (!ws.isEmpty() && !task.isEmpty()) {
                lastByKey.put(ws + "|" + task, r);
            }
        }
        int appendRow = history.getLastRowNum() + 1;
        String now = nowIso();
        for (Map<String, String> src : currentRows) {
            String ws = src.getOrDefault("workstream", "").trim();
            String task = src.getOrDefault("task", "").trim();
            if (ws.isEmpty() || task.isEmpty()) {
                continue;
            }
            String key = ws + "|" + task;
            HistState prev = readPrev(history, lastByKey.get(key));
            String status = src.getOrDefault("status", "").trim();
            String priority = src.getOrDefault("priority", "").trim();
            String percent = src.getOrDefault("percent_complete", "").trim();
            String csvLast = src.getOrDefault("last_updated", "").trim();
            String notes = src.getOrDefault("notes", "").trim();

            boolean changed = lastByKey.get(key) == null
                || !Objects.equals(prev.status(), status)
                || !Objects.equals(prev.priority(), priority)
                || !Objects.equals(prev.percent(), percent)
                || !Objects.equals(prev.csvLast(), csvLast)
                || !Objects.equals(prev.notes(), notes);
            if (!changed) {
                continue;
            }
            Row out = history.createRow(appendRow++);
            setCell(out, 0, now);
            setCell(out, 1, ws);
            setCell(out, 2, task);
            setCell(out, 3, prev.status());
            setCell(out, 4, status);
            setCell(out, 5, prev.priority());
            setCell(out, 6, priority);
            setCell(out, 7, prev.percent());
            setCell(out, 8, percent);
            setCell(out, 9, csvLast);
            setCell(out, 10, notes);
            lastByKey.put(key, appendRow - 1);
        }
    }

    private static HistState readPrev(Sheet history, Integer rowNum) {
        if (rowNum == null) {
            return new HistState("", "", "", "", "");
        }
        Row row = history.getRow(rowNum);
        return new HistState(
            getCell(row, 4).trim(),
            getCell(row, 6).trim(),
            getCell(row, 8).trim(),
            getCell(row, 9).trim(),
            getCell(row, 10).trim()
        );
    }

    private static double parsePercent(String raw) {
        String norm = Optional.ofNullable(raw).orElse("").replace("%", "").trim();
        if (norm.isEmpty()) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(norm);
        } catch (NumberFormatException nfe) {
            return 0.0d;
        }
    }

    private static String fmtPercent(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String asciiTrend(List<Double> values) {
        String chars = " .:-=+*#%@";
        StringBuilder sb = new StringBuilder();
        for (double v : values) {
            double c = Math.max(0.0, Math.min(100.0, v));
            int idx = (int) Math.round((chars.length() - 1) * c / 100.0);
            sb.append(chars.charAt(idx));
        }
        return sb.toString();
    }

    private static void rebuildTrend(Sheet trend, Sheet history) {
        clearSheet(trend);
        String[] headers = {
            "workstream",
            "task",
            "priority",
            "iteration",
            "updated_at",
            "percent_complete",
            "delta_percent",
            "trend_points",
            "trend_line_ascii",
            "trend_formula_graph"
        };
        Row hr = trend.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            setCell(hr, i, headers[i]);
        }
        Map<String, Integer> iterationByKey = new HashMap<>();
        Map<String, Double> prevPercentByKey = new HashMap<>();
        Map<String, List<Double>> pointsByKey = new LinkedHashMap<>();

        int outRow = 1;
        for (int i = 1; i <= history.getLastRowNum(); i++) {
            Row in = history.getRow(i);
            String ws = getCell(in, 1).trim();
            String task = getCell(in, 2).trim();
            if (ws.isEmpty() || task.isEmpty()) {
                continue;
            }
            String key = ws + "|" + task;
            int it = iterationByKey.getOrDefault(key, 0) + 1;
            iterationByKey.put(key, it);

            double point = parsePercent(getCell(in, 8));
            Double prev = prevPercentByKey.get(key);
            String delta = prev == null ? "" : fmtPercent(point - prev);
            prevPercentByKey.put(key, point);

            List<Double> points = pointsByKey.computeIfAbsent(key, ignored -> new ArrayList<>());
            points.add(point);

            Row out = trend.createRow(outRow + 1 - 1);
            setCell(out, 0, ws);
            setCell(out, 1, task);
            setCell(out, 2, getCell(in, 6).trim());
            setCell(out, 3, Integer.toString(it));
            String updatedAt = getCell(in, 9).trim();
            if (updatedAt.isEmpty()) {
                updatedAt = getCell(in, 0).trim();
            }
            setCell(out, 4, updatedAt);
            setCell(out, 5, fmtPercent(point));
            setCell(out, 6, delta);
            setCell(out, 7, String.join(",", points.stream().map(ProjectPlanWorkbookUpdater::fmtPercent).toList()));
            setCell(out, 8, asciiTrend(points));
            outRow++;
        }
    }

    private static void rebuildGraph(Sheet graph, Sheet trend) {
        clearSheet(graph);
        Map<String, Map<Integer, Double>> pointsByTask = new LinkedHashMap<>();
        int maxIteration = 1;
        for (int r = 1; r <= trend.getLastRowNum(); r++) {
            Row row = trend.getRow(r);
            if (row == null) {
                continue;
            }
            String ws = getCell(row, 0).trim();
            String task = getCell(row, 1).trim();
            if (ws.isEmpty() || task.isEmpty()) {
                continue;
            }
            String label = ws + " | " + task;
            int it;
            try {
                it = Integer.parseInt(getCell(row, 3).trim());
            } catch (NumberFormatException nfe) {
                it = 1;
            }
            maxIteration = Math.max(maxIteration, it);
            double pct = parsePercent(getCell(row, 5));
            pointsByTask.computeIfAbsent(label, ignored -> new HashMap<>()).put(it, pct);
        }

        Row header = graph.createRow(0);
        setCell(header, 0, "iteration");
        List<String> labels = new ArrayList<>(pointsByTask.keySet());
        labels.sort(Comparator.naturalOrder());
        for (int c = 0; c < labels.size(); c++) {
            setCell(header, c + 1, labels.get(c));
        }
        for (int it = 1; it <= maxIteration; it++) {
            Row row = graph.createRow(it);
            setCell(row, 0, Integer.toString(it));
            for (int c = 0; c < labels.size(); c++) {
                Double v = pointsByTask.get(labels.get(c)).get(it);
                setCell(row, c + 1, v == null ? "" : fmtPercent(v));
            }
        }

        int summaryStart = maxIteration + 3;
        Row sh = graph.createRow(summaryStart);
        setCell(sh, 0, "task");
        setCell(sh, 1, "trend_line_ascii");
        setCell(sh, 2, "latest_percent");
        int out = summaryStart + 1;
        for (String label : labels) {
            List<Double> vals = new ArrayList<>();
            double latest = 0.0;
            for (int i = 1; i <= maxIteration; i++) {
                Double v = pointsByTask.get(label).get(i);
                if (v == null) {
                    v = latest;
                }
                latest = v;
                vals.add(v);
            }
            Row row = graph.createRow(out++);
            setCell(row, 0, label);
            setCell(row, 1, asciiTrend(vals));
            setCell(row, 2, fmtPercent(latest));
        }
    }

    private static void syncExtendedProperties(Workbook workbook) {
        if (!(workbook instanceof XSSFWorkbook xwb)) {
            return;
        }
        List<String> sheetNames = new ArrayList<>();
        for (int i = 0; i < xwb.getNumberOfSheets(); i++) {
            sheetNames.add(xwb.getSheetName(i));
        }

        POIXMLProperties props = xwb.getProperties();
        CTProperties ext = props.getExtendedProperties().getUnderlyingProperties();

        if (!ext.isSetHeadingPairs()) {
            ext.addNewHeadingPairs();
        }
        CTVectorVariant headingPairs = ext.isSetHeadingPairs() ? ext.getHeadingPairs() : ext.addNewHeadingPairs();
        CTVector headingVector = headingPairs.getVector() != null
            ? headingPairs.getVector()
            : headingPairs.addNewVector();
        while (headingVector.sizeOfVariantArray() > 0) {
            headingVector.removeVariant(0);
        }
        headingVector.setSize(2);

        CTVariant v1 = headingVector.addNewVariant();
        v1.setLpstr("Worksheets");
        CTVariant v2 = headingVector.addNewVariant();
        v2.setI4(sheetNames.size());

        CTVectorLpstr titlesOfParts = ext.isSetTitlesOfParts() ? ext.getTitlesOfParts() : ext.addNewTitlesOfParts();
        CTVector titles = titlesOfParts.getVector() != null
            ? titlesOfParts.getVector()
            : titlesOfParts.addNewVector();
        while (titles.sizeOfLpstrArray() > 0) {
            titles.removeLpstr(0);
        }
        for (String name : sheetNames) {
            titles.addLpstr(name);
        }
        titles.setSize(sheetNames.size());
    }
}
