package com.upland.connect.afp.tools;

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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class BoilerplateSyncWorkbookUpdater {

    private static final String SHEET_CANDIDATES = "Candidates";
    private static final String SHEET_FILES = "Package Files";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private BoilerplateSyncWorkbookUpdater() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);
        Path packagesDir = optionalPath(cli, "--packages");
        Path dbPath = optionalPath(cli, "--db");
        Path xlsxPath = requiredPath(cli, "--xlsx");
        prepareWorkbookPath(xlsxPath);

        List<Candidate> candidates;
        if (dbPath != null) {
            candidates = readCandidatesFromDatabase(dbPath);
        } else if (packagesDir != null) {
            candidates = scanCandidates(packagesDir);
        } else {
            throw new IllegalArgumentException("Provide either --db or --packages");
        }
        Map<String, ManualState> manual = readManualState(xlsxPath);

        try (Workbook wb = openWorkbook(xlsxPath)) {
            Sheet candidatesSheet = ensureSheet(wb, SHEET_CANDIDATES);
            Sheet filesSheet = ensureSheet(wb, SHEET_FILES);
            writeCandidates(candidatesSheet, candidates, manual);
            writePackageFiles(filesSheet, candidates);
            try (OutputStream os = Files.newOutputStream(xlsxPath)) {
                wb.write(os);
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
        if (v == null || v.isBlank()) {
            return null;
        }
        return Path.of(v);
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
            wb.createSheet(SHEET_CANDIDATES);
            wb.createSheet(SHEET_FILES);
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
        return ISO.format(Instant.now().atOffset(ZoneOffset.UTC).withNano(0));
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

    private static Map<String, ManualState> readManualState(Path xlsxPath) throws IOException {
        Map<String, ManualState> out = new LinkedHashMap<>();
        try (Workbook wb = openWorkbook(xlsxPath)) {
            Sheet sheet = wb.getSheet(SHEET_CANDIDATES);
            if (sheet == null) {
                return out;
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                String candidateId = getCell(row, 0).trim();
                if (candidateId.isEmpty()) {
                    continue;
                }
                out.put(candidateId, new ManualState(
                    getCell(row, 8).trim(),
                    getCell(row, 9).trim(),
                    getCell(row, 10).trim()
                ));
            }
        }
        return out;
    }

    private static List<Candidate> scanCandidates(Path packagesDir) throws IOException {
        if (!Files.exists(packagesDir) || !Files.isDirectory(packagesDir)) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(packagesDir)) {
            stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                .forEach(dirs::add);
        }
        List<Candidate> out = new ArrayList<>(dirs.size());
        for (Path dir : dirs) {
            out.add(scanCandidate(dir, packagesDir));
        }
        return out;
    }

    private static List<Candidate> readCandidatesFromDatabase(Path dbPath) throws Exception {
        if (!Files.exists(dbPath)) {
            return List.of();
        }
        Map<String, List<PackageFile>> filesByCandidate = new HashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                 "select candidate_id, file_path, size_bytes from package_files order by candidate_id, file_path"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String candidateId = text(rs.getString(1));
                filesByCandidate.computeIfAbsent(candidateId, ignored -> new ArrayList<>())
                    .add(new PackageFile(text(rs.getString(2)), Long.toString(rs.getLong(3))));
            }
        }

        List<Candidate> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                 "select candidate_id, package_id, target_repo, source_repo, created_at, summary, package_zip, " +
                     "patch_count, manifest_file_count, supersedes_json from package_candidates order by candidate_id"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String candidateId = text(rs.getString(1));
                out.add(new Candidate(
                    candidateId,
                    text(rs.getString(2)),
                    text(rs.getString(3)),
                    text(rs.getString(4)),
                    text(rs.getString(5)),
                    text(rs.getString(6)),
                    text(rs.getString(7)),
                    rs.getInt(8),
                    rs.getInt(9),
                    parseJsonStringArray(text(rs.getString(10))),
                    filesByCandidate.getOrDefault(candidateId, List.of())
                ));
            }
        }
        return out;
    }

    private static Candidate scanCandidate(Path dir, Path packagesRoot) throws IOException {
        String candidateId = dir.getFileName().toString();
        String targetRepo = packagesRoot.getFileName().toString();
        String created = ISO.format(Files.getLastModifiedTime(dir).toInstant().atOffset(ZoneOffset.UTC));
        Path readme = dir.resolve("README.md");
        Path manifest = dir.resolve("package-manifest.json");
        String summary = readSummary(readme);
        String packageId = readJsonString(manifest, "packageId");
        String sourceRepo = readJsonString(manifest, "sourceRepo");
        String targetFromManifest = readJsonString(manifest, "targetRepo");
        List<String> supersedes = readJsonArrayStrings(manifest, "supersedes");
        int patchCount = countFiles(dir.resolve("patches"), ".patch");
        int manifestFileCount = countManifestFiles(manifest);
        List<PackageFile> files = listFiles(dir);
        String zipName = candidateId + ".zip";
        Path zipPath = packagesRoot.resolve(zipName);
        String zipRel = Files.exists(zipPath) ? packagesRoot.relativize(zipPath).toString().replace('\\', '/') : "";
        return new Candidate(
            candidateId,
            packageId.isBlank() ? candidateId : packageId,
            targetFromManifest.isBlank() ? targetRepo : targetFromManifest,
            sourceRepo,
            created,
            summary,
            zipRel,
            patchCount,
            manifestFileCount,
            supersedes,
            files
        );
    }

    private static String readSummary(Path readme) throws IOException {
        if (!Files.exists(readme)) {
            return "";
        }
        List<String> lines = Files.readAllLines(readme, StandardCharsets.UTF_8);
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                continue;
            }
            return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
        }
        return "";
    }

    private static String readJsonString(Path json, String key) throws IOException {
        if (!Files.exists(json)) {
            return "";
        }
        String text = Files.readString(json, StandardCharsets.UTF_8);
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private static List<String> readJsonArrayStrings(Path json, String key) throws IOException {
        if (!Files.exists(json)) {
            return List.of();
        }
        String text = Files.readString(json, StandardCharsets.UTF_8);
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return List.of();
        }
        String body = m.group(1);
        Matcher valueMatcher = Pattern.compile("\"([^\"]+)\"").matcher(body);
        List<String> out = new ArrayList<>();
        while (valueMatcher.find()) {
            out.add(valueMatcher.group(1).trim());
        }
        return out;
    }

    private static List<String> parseJsonStringArray(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        Matcher valueMatcher = Pattern.compile("\"([^\"]+)\"").matcher(jsonArray);
        List<String> out = new ArrayList<>();
        while (valueMatcher.find()) {
            out.add(valueMatcher.group(1).trim());
        }
        return out;
    }

    private static int countManifestFiles(Path manifest) throws IOException {
        if (!Files.exists(manifest)) {
            return 0;
        }
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"path\"\\s*:").matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    private static int countFiles(Path dir, String suffix) throws IOException {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.walk(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                .count();
        }
    }

    private static List<PackageFile> listFiles(Path packageDir) throws IOException {
        List<PackageFile> out = new ArrayList<>();
        Files.walkFileTree(packageDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = packageDir.relativize(file).toString().replace('\\', '/');
                out.add(new PackageFile(rel, Long.toString(attrs.size())));
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparing(PackageFile::path));
        return out;
    }

    private static void writeCandidates(Sheet sheet, List<Candidate> candidates, Map<String, ManualState> manual) {
        clearSheet(sheet);
        Set<String> superseded = new HashSet<>();
        for (Candidate c : candidates) {
            superseded.addAll(c.supersedes());
        }
        String[] headers = {
            "candidate_id",
            "package_id",
            "target_repo",
            "source_repo",
            "created_at",
            "summary",
            "package_zip",
            "patch_count",
            "decision",
            "state",
            "owner_notes",
            "recommended_scope",
            "manifest_file_count",
            "last_seen"
        };
        Row hr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            setCell(hr, i, headers[i]);
        }
        int rowNo = 1;
        String now = nowIso();
        for (Candidate c : candidates) {
            ManualState st = manual.getOrDefault(c.candidateId(), ManualState.empty());
            Row row = sheet.createRow(rowNo++);
            setCell(row, 0, c.candidateId());
            setCell(row, 1, c.packageId());
            setCell(row, 2, c.targetRepo());
            setCell(row, 3, c.sourceRepo());
            setCell(row, 4, c.createdAt());
            setCell(row, 5, c.summary());
            setCell(row, 6, c.packageZip());
            setCell(row, 7, Integer.toString(c.patchCount()));
            setCell(row, 8, st.decision());
            setCell(row, 9, st.state());
            setCell(row, 10, st.ownerNotes());
            setCell(row, 11, superseded.contains(c.candidateId()) ? "superseded" : "review");
            setCell(row, 12, Integer.toString(c.manifestFileCount()));
            setCell(row, 13, now);
        }
        if (rowNo < 2) {
            sheet.createRow(1);
            rowNo = 2;
        }
        sheet.setAutoFilter(new CellRangeAddress(0, rowNo - 1, 0, headers.length - 1));
    }

    private static void writePackageFiles(Sheet sheet, List<Candidate> candidates) {
        clearSheet(sheet);
        String[] headers = {"candidate_id", "file_path", "size_bytes"};
        Row hr = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            setCell(hr, i, headers[i]);
        }
        int rowNo = 1;
        for (Candidate c : candidates) {
            for (PackageFile f : c.files()) {
                Row row = sheet.createRow(rowNo++);
                setCell(row, 0, c.candidateId());
                setCell(row, 1, f.path());
                setCell(row, 2, f.sizeBytes());
            }
        }
        if (rowNo < 2) {
            sheet.createRow(1);
            rowNo = 2;
        }
        sheet.setAutoFilter(new CellRangeAddress(0, rowNo - 1, 0, headers.length - 1));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private record Candidate(String candidateId,
                             String packageId,
                             String targetRepo,
                             String sourceRepo,
                             String createdAt,
                             String summary,
                             String packageZip,
                             int patchCount,
                             int manifestFileCount,
                             List<String> supersedes,
                             List<PackageFile> files) {}

    private record PackageFile(String path, String sizeBytes) {}

    private record ManualState(String decision, String state, String ownerNotes) {
        static ManualState empty() {
            return new ManualState("", "", "");
        }
    }
}
