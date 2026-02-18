package com.upland.connect.afp.tools;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PolicyGovernanceWorkbookUpdater {

    private static final String SHEET_EVENTS = "Governance Events";
    private static final List<String> REQUIRED_COLUMNS = List.of(
        "event_id",
        "event_date",
        "phase",
        "status",
        "checkpoint_id",
        "issue_id",
        "summary",
        "evidence",
        "updated_by"
    );

    private PolicyGovernanceWorkbookUpdater() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path csvPath = requiredPath(cli, "--csv");
        Path xlsxPath = requiredPath(cli, "--xlsx");

        List<Map<String, String>> rows = readCsv(csvPath);
        prepareWorkbookPath(xlsxPath);
        try (Workbook workbook = openWorkbook(xlsxPath)) {
            Sheet events = ensureSheet(workbook, SHEET_EVENTS);
            writeEvents(events, rows);
            try (OutputStream os = Files.newOutputStream(xlsxPath)) {
                workbook.write(os);
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--")) {
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + key);
            }
            out.put(key, args[++i]);
        }
        return out;
    }

    private static Path requiredPath(Map<String, String> cli, String key) {
        String value = cli.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return Path.of(value);
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
            for (String column : REQUIRED_COLUMNS) {
                if (!headers.contains(column)) {
                    throw new IllegalArgumentException("CSV missing required column: " + column);
                }
            }
            List<Map<String, String>> out = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String column : REQUIRED_COLUMNS) {
                    row.put(column, Optional.ofNullable(record.get(column)).orElse("").trim());
                }
                out.add(row);
            }
            return out;
        }
    }

    private static void prepareWorkbookPath(Path xlsxPath) throws IOException {
        if (Files.exists(xlsxPath)) {
            return;
        }
        Path parent = xlsxPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Workbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(xlsxPath)) {
            wb.createSheet(SHEET_EVENTS);
            wb.write(os);
        }
    }

    private static Workbook openWorkbook(Path xlsxPath) throws IOException {
        try (InputStream is = Files.newInputStream(xlsxPath)) {
            return new XSSFWorkbook(is);
        }
    }

    private static Sheet ensureSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet != null) {
            return sheet;
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

    private static void writeEvents(Sheet sheet, List<Map<String, String>> rows) {
        clearSheet(sheet);
        Row header = sheet.createRow(0);
        for (int i = 0; i < REQUIRED_COLUMNS.size(); i++) {
            setCell(header, i, REQUIRED_COLUMNS.get(i));
        }
        int rowNo = 1;
        for (Map<String, String> src : rows) {
            Row row = sheet.createRow(rowNo++);
            for (int i = 0; i < REQUIRED_COLUMNS.size(); i++) {
                setCell(row, i, src.get(REQUIRED_COLUMNS.get(i)));
            }
        }
        if (rowNo < 2) {
            sheet.createRow(1);
            rowNo = 2;
        }
        sheet.setAutoFilter(new CellRangeAddress(0, rowNo - 1, 0, REQUIRED_COLUMNS.size() - 1));
    }

    private static void setCell(Row row, int index, String value) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            cell = row.createCell(index);
        }
        cell.setCellValue(value == null ? "" : value);
    }
}
