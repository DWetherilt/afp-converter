package com.upland.connect.afp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StateDatabaseTool {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> ISSUE_COLUMNS = List.of(
        "id", "status", "severity", "title", "components", "opened_on", "last_reviewed", "owner", "notes"
    );
    private static final List<String> PROGRESS_COLUMNS = List.of(
        "workstream", "task", "priority", "status", "percent_complete", "last_updated", "notes"
    );
    private static final List<String> GOVERNANCE_COLUMNS = List.of(
        "event_id", "event_date", "phase", "status", "checkpoint_id", "issue_id", "summary", "evidence", "updated_by"
    );
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private StateDatabaseTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing command. Use: sync-project|sync-boilerplate|export-progress-json|issues-tickle|issues-effectiveness|governance-alerts|version-control-ledger");
        }
        String command = args[0];
        Map<String, String> cli = parseArgs(args, 1);
        int exit = switch (command) {
            case "sync-project" -> runSyncProject(cli);
            case "sync-boilerplate" -> runSyncBoilerplate(cli);
            case "export-progress-json" -> runExportProgressJson(cli);
            case "issues-tickle" -> runIssuesTickle(cli);
            case "issues-effectiveness" -> runIssuesEffectiveness(cli);
            case "governance-alerts" -> runGovernanceAlerts(cli);
            case "version-control-ledger" -> runVersionControlLedger(cli);
            default -> throw new IllegalArgumentException("Unsupported command: " + command);
        };
        if (exit != 0) {
            System.exit(exit);
        }
    }

    private static Map<String, String> parseArgs(String[] args, int start) {
        Map<String, String> out = new HashMap<>();
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if ("--enforce".equals(token)) {
                out.put("--enforce", "true");
                continue;
            }
            if (!token.startsWith("--")) {
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + token);
            }
            out.put(token, args[++i]);
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

    private static Path optionalPath(Map<String, String> cli, String key) {
        String value = cli.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value);
    }

    private static int runSyncProject(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path issuesPath = requiredPath(cli, "--issues");
        Path progressPath = requiredPath(cli, "--progress");
        Path fidelityPath = optionalPath(cli, "--fidelity");
        Path governanceEventsPath = optionalPath(cli, "--governance-events");
        ensureParent(dbPath);
        try (Connection conn = connect(dbPath)) {
            initProjectSchema(conn);
            syncIssues(conn, issuesPath);
            syncProgress(conn, progressPath);
            syncFidelity(conn, fidelityPath);
            syncGovernanceEvents(conn, governanceEventsPath);
            syncVersionControlState(conn);
        }
        return 0;
    }

    private static int runSyncBoilerplate(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path packagesDir = requiredPath(cli, "--packages");
        ensureParent(dbPath);
        try (Connection conn = connect(dbPath)) {
            initBoilerplateSchema(conn);
            syncBoilerplatePackages(conn, packagesDir);
        }
        return 0;
    }

    private static int runExportProgressJson(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path jsonPath = requiredPath(cli, "--json");
        ensureParent(jsonPath);

        List<Map<String, String>> tasks = new ArrayList<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select workstream, task, priority, status, percent_complete, last_updated, notes " +
                     "from plan_tasks order by row_index asc"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("workstream", text(rs.getString(1)));
                row.put("task", text(rs.getString(2)));
                row.put("priority", text(rs.getString(3)));
                row.put("status", text(rs.getString(4)));
                row.put("percent_complete", text(rs.getString(5)));
                row.put("last_updated", text(rs.getString(6)));
                row.put("notes", text(rs.getString(7)));
                tasks.add(row);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("taskCount", tasks.size());
        payload.put("tasks", tasks);
        payload.put("followUps", List.of(Map.of(
            "id", "restored-workbook-content-review",
            "status", "pending",
            "note", "Circle back and review restored workbook content against JSON/CSV sources."
        )));
        writeJson(jsonPath, payload);
        return 0;
    }

    private static int runIssuesTickle(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path issuesPath = requiredPath(cli, "--issues");
        Path outputPath = requiredPath(cli, "--output");
        boolean enforce = "true".equalsIgnoreCase(cli.getOrDefault("--enforce", "false"));
        ensureParent(outputPath);

        List<String> changed = changedPaths();
        Set<String> changedSet = new LinkedHashSet<>(changed);
        String issuesPathPosix = issuesPath.toString().replace('\\', '/');
        boolean issuesChanged = changedSet.stream().anyMatch(ch -> changeCoversTarget(ch, issuesPathPosix));

        List<Map<String, Object>> triggered = new ArrayList<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement("select id, status, title, components from issues order by id asc");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String components = text(rs.getString(4));
                if (components.isBlank()) {
                    continue;
                }
                List<String> patterns = splitPipe(components);
                Set<String> matches = new LinkedHashSet<>();
                for (String path : changed) {
                    for (String pattern : patterns) {
                        if (globMatch(path, pattern) || changeCoversTarget(path, pattern)) {
                            matches.add(path);
                            break;
                        }
                    }
                }
                if (!matches.isEmpty()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", text(rs.getString(1)));
                    t.put("status", text(rs.getString(2)));
                    t.put("title", text(rs.getString(3)));
                    t.put("matchedComponents", new ArrayList<>(matches));
                    triggered.add(t);
                }
            }
        }

        boolean requiresUpdate = !triggered.isEmpty() && !issuesChanged;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("issuesLogPath", issuesPathPosix);
        payload.put("issuesLogTouchedInWorkingTree", issuesChanged);
        payload.put("changedPathCount", changed.size());
        payload.put("triggeredIssueCount", triggered.size());
        payload.put("requiresIssuesLogUpdate", requiresUpdate);
        payload.put("triggeredIssues", triggered);
        payload.put(
            "message",
            requiresUpdate
                ? "Issue-referenced components are being modified; update docs/issues-log.csv in the same change."
                : "No issue-log tickle required."
        );
        writeJson(outputPath, payload);
        return (enforce && requiresUpdate) ? 2 : 0;
    }

    private static int runIssuesEffectiveness(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path ticklePath = requiredPath(cli, "--tickle");
        Path outputPath = requiredPath(cli, "--output");
        ensureParent(outputPath);

        JsonObject tickle = readJsonObject(ticklePath);
        List<Map<String, String>> issues = new ArrayList<>();
        double percentSum = 0.0;
        int percentCount = 0;
        Double fidelityScore = null;
        Double pixelDiff = null;
        Double tokenRecall = null;
        Double tokenPrecision = null;

        try (Connection conn = connect(dbPath)) {
            try (PreparedStatement ps = conn.prepareStatement(
                "select id, status, severity, title, owner, last_reviewed from issues order by id asc"
            ); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> i = new LinkedHashMap<>();
                    i.put("id", text(rs.getString(1)));
                    i.put("status", text(rs.getString(2)));
                    i.put("severity", text(rs.getString(3)));
                    i.put("title", text(rs.getString(4)));
                    i.put("owner", text(rs.getString(5)));
                    i.put("last_reviewed", text(rs.getString(6)));
                    issues.add(i);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("select percent_complete from plan_tasks");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    percentSum += parsePercent(rs.getString(1));
                    percentCount++;
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "select fidelity_score, average_pixel_diff_ratio, token_recall, token_precision " +
                    "from fidelity_snapshot where id = 1"
            ); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    fidelityScore = numberOrNull(rs, 1);
                    pixelDiff = numberOrNull(rs, 2);
                    tokenRecall = numberOrNull(rs, 3);
                    tokenPrecision = numberOrNull(rs, 4);
                }
            }
        }

        List<Map<String, String>> openIssues = issues.stream()
            .filter(i -> isOpenStatus(i.get("status")))
            .toList();
        List<Map<String, String>> highOpen = openIssues.stream()
            .filter(i -> "high".equalsIgnoreCase(text(i.get("severity"))))
            .toList();

        boolean requiresUpdate = tickle.has("requiresIssuesLogUpdate") && tickle.get("requiresIssuesLogUpdate").getAsBoolean();
        int triggeredCount = tickle.has("triggeredIssueCount") ? tickle.get("triggeredIssueCount").getAsInt() : 0;
        List<String> signals = new ArrayList<>();
        if (requiresUpdate) {
            signals.add("Issue log update required for changed issue-referenced components.");
        }
        if (fidelityScore != null && fidelityScore < 0.95d) {
            signals.add("Fidelity score below target (0.95).");
        }
        if (pixelDiff != null && pixelDiff > 0.03d) {
            signals.add("Average pixel diff ratio above target (0.03).");
        }
        if (tokenRecall != null && tokenRecall < 0.98d) {
            signals.add("Token recall below target (0.98).");
        }
        if (tokenPrecision != null && tokenPrecision < 0.98d) {
            signals.add("Token precision below target (0.98).");
        }
        double avgPercent = percentCount > 0 ? (percentSum / percentCount) : 0.0;
        if (avgPercent < 85.0d) {
            signals.add("Average plan task completion below 85%.");
        }
        if (!highOpen.isEmpty()) {
            signals.add("High-severity issues remain open.");
        }

        List<Map<String, String>> openHighSeverity = new ArrayList<>();
        for (Map<String, String> i : highOpen) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", i.get("id"));
            entry.put("title", i.get("title"));
            entry.put("owner", i.get("owner"));
            entry.put("last_reviewed", i.get("last_reviewed"));
            openHighSeverity.add(entry);
        }

        Map<String, Object> fidelityMap = new LinkedHashMap<>();
        fidelityMap.put("fidelityScore", fidelityScore);
        fidelityMap.put("averagePixelDiffRatio", pixelDiff);
        fidelityMap.put("tokenRecall", tokenRecall);
        fidelityMap.put("tokenPrecision", tokenPrecision);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("summary", Map.of(
            "issueCount", issues.size(),
            "openIssueCount", openIssues.size(),
            "highSeverityOpenCount", highOpen.size(),
            "triggeredIssueCount", triggeredCount,
            "requiresIssuesLogUpdate", requiresUpdate,
            "taskCount", percentCount,
            "averageTaskPercentComplete", round2(avgPercent)
        ));
        payload.put("fidelity", fidelityMap);
        payload.put("signals", signals);
        payload.put("triggeredIssues", readArrayOrEmpty(tickle, "triggeredIssues"));
        payload.put("openHighSeverityIssues", openHighSeverity);
        writeJson(outputPath, payload);
        return 0;
    }

    private static int runGovernanceAlerts(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path outputPath = requiredPath(cli, "--output");
        boolean enforce = "true".equalsIgnoreCase(cli.getOrDefault("--enforce", "false"));
        ensureParent(outputPath);

        List<Map<String, String>> activeBreaches = new ArrayList<>();
        List<Map<String, String>> trustEntries = new ArrayList<>();
        try (Connection conn = connect(dbPath)) {
            try (PreparedStatement ps = conn.prepareStatement(
                "select event_id, event_date, phase, status, checkpoint_id, issue_id, summary, evidence, updated_by " +
                    "from governance_events order by event_date desc, event_id desc"
            ); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> event = new LinkedHashMap<>();
                    event.put("event_id", text(rs.getString(1)));
                    event.put("event_date", text(rs.getString(2)));
                    event.put("phase", text(rs.getString(3)));
                    event.put("status", text(rs.getString(4)));
                    event.put("checkpoint_id", text(rs.getString(5)));
                    event.put("issue_id", text(rs.getString(6)));
                    event.put("summary", text(rs.getString(7)));
                    event.put("evidence", text(rs.getString(8)));
                    event.put("updated_by", text(rs.getString(9)));
                    String status = event.get("status").toLowerCase(Locale.ROOT);
                    if (status.equals("open") || status.equals("in_progress") || status.equals("breach")) {
                        activeBreaches.add(event);
                    }
                    String summary = event.get("summary").toLowerCase(Locale.ROOT);
                    String phase = event.get("phase").toLowerCase(Locale.ROOT);
                    if (summary.contains("trust") || phase.contains("trust")) {
                        trustEntries.add(event);
                    }
                }
            }
        }

        boolean hasBreach = !activeBreaches.isEmpty();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("hasActiveGovernanceBreach", hasBreach);
        payload.put("activeBreachCount", activeBreaches.size());
        payload.put("trustEventCount", trustEntries.size());
        payload.put("activeBreaches", activeBreaches);
        payload.put("trustEvents", trustEntries);
        payload.put("message", hasBreach
            ? "Governance breach/in-progress items require human visibility."
            : "No active governance breaches.");
        writeJson(outputPath, payload);
        return (enforce && hasBreach) ? 2 : 0;
    }

    private static int runVersionControlLedger(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path outputPath = requiredPath(cli, "--output");
        ensureParent(outputPath);

        Map<String, Object> repo = new LinkedHashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        try (Connection conn = connect(dbPath)) {
            try (PreparedStatement ps = conn.prepareStatement(
                "select git_head, git_branch, is_dirty, tracked_count, modified_count, untracked_count, deleted_count, updated_at " +
                    "from repo_vcs_snapshot where id = 1"
            ); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    repo.put("gitHead", text(rs.getString(1)));
                    repo.put("gitBranch", text(rs.getString(2)));
                    repo.put("isDirty", rs.getInt(3) != 0);
                    repo.put("trackedCount", rs.getInt(4));
                    repo.put("modifiedCount", rs.getInt(5));
                    repo.put("untrackedCount", rs.getInt(6));
                    repo.put("deletedCount", rs.getInt(7));
                    repo.put("updatedAt", text(rs.getString(8)));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "select path, tracked, status_code, exists_on_disk, size_bytes, sha256 from repo_file_state order by path asc"
            ); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("path", text(rs.getString(1)));
                    row.put("tracked", rs.getInt(2) != 0);
                    row.put("statusCode", text(rs.getString(3)));
                    row.put("existsOnDisk", rs.getInt(4) != 0);
                    row.put("sizeBytes", rs.getLong(5));
                    row.put("sha256", text(rs.getString(6)));
                    files.add(row);
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("repo", repo);
        payload.put("fileCount", files.size());
        payload.put("files", files);
        payload.put("note", "Git is the canonical version-control source; this ledger is an auditable mirror in project state.");
        writeJson(outputPath, payload);
        return 0;
    }

    private static Connection connect(Path dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private static void initProjectSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("pragma journal_mode = wal");
            st.execute("""
                create table if not exists issues (
                    id text primary key,
                    status text not null default '',
                    severity text not null default '',
                    title text not null default '',
                    components text not null default '',
                    opened_on text not null default '',
                    last_reviewed text not null default '',
                    owner text not null default '',
                    notes text not null default '',
                    updated_at text not null
                )
                """);
            st.execute("""
                create table if not exists plan_tasks (
                    row_index integer primary key,
                    task_key text not null,
                    workstream text not null default '',
                    task text not null default '',
                    priority text not null default '',
                    status text not null default '',
                    percent_complete text not null default '',
                    last_updated text not null default '',
                    notes text not null default '',
                    updated_at text not null
                )
                """);
            st.execute("create index if not exists idx_plan_task_key on plan_tasks(task_key)");
            st.execute("""
                create table if not exists fidelity_snapshot (
                    id integer primary key check(id = 1),
                    fidelity_score real,
                    average_pixel_diff_ratio real,
                    token_recall real,
                    token_precision real,
                    source_path text not null default '',
                    updated_at text not null
                )
                """);
            st.execute("""
                create table if not exists governance_events (
                    event_id text primary key,
                    event_date text not null default '',
                    phase text not null default '',
                    status text not null default '',
                    checkpoint_id text not null default '',
                    issue_id text not null default '',
                    summary text not null default '',
                    evidence text not null default '',
                    updated_by text not null default '',
                    updated_at text not null
                )
                """);
            st.execute("""
                create table if not exists repo_vcs_snapshot (
                    id integer primary key check(id = 1),
                    git_head text not null default '',
                    git_branch text not null default '',
                    is_dirty integer not null default 0,
                    tracked_count integer not null default 0,
                    modified_count integer not null default 0,
                    untracked_count integer not null default 0,
                    deleted_count integer not null default 0,
                    updated_at text not null
                )
                """);
            st.execute("""
                create table if not exists repo_file_state (
                    path text primary key,
                    tracked integer not null default 0,
                    status_code text not null default '',
                    exists_on_disk integer not null default 0,
                    size_bytes integer not null default 0,
                    sha256 text not null default '',
                    updated_at text not null
                )
                """);
            st.execute("create index if not exists idx_repo_file_status on repo_file_state(status_code)");
        }
    }

    private static void initBoilerplateSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("pragma journal_mode = wal");
            st.execute("""
                create table if not exists package_candidates (
                    candidate_id text primary key,
                    package_id text not null default '',
                    target_repo text not null default '',
                    source_repo text not null default '',
                    created_at text not null default '',
                    summary text not null default '',
                    package_zip text not null default '',
                    patch_count integer not null default 0,
                    manifest_file_count integer not null default 0,
                    supersedes_json text not null default '[]',
                    updated_at text not null
                )
                """);
            st.execute("""
                create table if not exists package_files (
                    candidate_id text not null,
                    file_path text not null,
                    size_bytes integer not null default 0,
                    updated_at text not null,
                    primary key(candidate_id, file_path)
                )
                """);
        }
    }

    private static void syncIssues(Connection conn, Path csvPath) throws Exception {
        List<Map<String, String>> rows = readCsv(csvPath, ISSUE_COLUMNS);
        String now = nowIso();
        conn.setAutoCommit(false);
        try (Statement clear = conn.createStatement()) {
            clear.execute("delete from issues");
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "insert into issues(id,status,severity,title,components,opened_on,last_reviewed,owner,notes,updated_at) " +
                "values(?,?,?,?,?,?,?,?,?,?)"
        )) {
            for (Map<String, String> row : rows) {
                for (int i = 0; i < ISSUE_COLUMNS.size(); i++) {
                    ps.setString(i + 1, row.get(ISSUE_COLUMNS.get(i)));
                }
                ps.setString(10, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static void syncProgress(Connection conn, Path csvPath) throws Exception {
        List<Map<String, String>> rows = readCsv(csvPath, PROGRESS_COLUMNS);
        String now = nowIso();
        conn.setAutoCommit(false);
        try (Statement clear = conn.createStatement()) {
            clear.execute("delete from plan_tasks");
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "insert into plan_tasks(row_index,task_key,workstream,task,priority,status,percent_complete,last_updated,notes,updated_at) " +
                "values(?,?,?,?,?,?,?,?,?,?)"
        )) {
            int rowIndex = 1;
            for (Map<String, String> row : rows) {
                String taskKey = row.get("workstream") + "|" + row.get("task");
                ps.setInt(1, rowIndex++);
                ps.setString(2, taskKey);
                ps.setString(3, row.get("workstream"));
                ps.setString(4, row.get("task"));
                ps.setString(5, row.get("priority"));
                ps.setString(6, row.get("status"));
                ps.setString(7, row.get("percent_complete"));
                ps.setString(8, row.get("last_updated"));
                ps.setString(9, row.get("notes"));
                ps.setString(10, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static void syncFidelity(Connection conn, Path fidelityPath) throws Exception {
        JsonObject obj = readJsonObject(fidelityPath);
        Double fidelityScore = numberAtPath(obj, List.of("fidelityScore"), List.of("summary", "fidelityScore"));
        Double pixelDiff = numberAtPath(obj, List.of("averagePixelDiffRatio"), List.of("summary", "averagePixelDiffRatio"));
        Double tokenRecall = numberAtPath(obj, List.of("tokenRecall"), List.of("text", "tokenRecall"));
        Double tokenPrecision = numberAtPath(obj, List.of("tokenPrecision"), List.of("text", "tokenPrecision"));

        try (PreparedStatement ps = conn.prepareStatement(
            "insert into fidelity_snapshot(id,fidelity_score,average_pixel_diff_ratio,token_recall,token_precision,source_path,updated_at) " +
                "values(1,?,?,?,?,?,?) " +
                "on conflict(id) do update set " +
                "fidelity_score=excluded.fidelity_score, " +
                "average_pixel_diff_ratio=excluded.average_pixel_diff_ratio, " +
                "token_recall=excluded.token_recall, " +
                "token_precision=excluded.token_precision, " +
                "source_path=excluded.source_path, " +
                "updated_at=excluded.updated_at"
        )) {
            bindNumber(ps, 1, fidelityScore);
            bindNumber(ps, 2, pixelDiff);
            bindNumber(ps, 3, tokenRecall);
            bindNumber(ps, 4, tokenPrecision);
            ps.setString(5, fidelityPath == null ? "" : fidelityPath.toString().replace('\\', '/'));
            ps.setString(6, nowIso());
            ps.executeUpdate();
        }
    }

    private static void syncGovernanceEvents(Connection conn, Path csvPath) throws Exception {
        if (csvPath == null || !Files.exists(csvPath)) {
            return;
        }
        List<Map<String, String>> rows = readCsv(csvPath, GOVERNANCE_COLUMNS);
        String now = nowIso();
        conn.setAutoCommit(false);
        try (Statement clear = conn.createStatement()) {
            clear.execute("delete from governance_events");
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "insert into governance_events(event_id,event_date,phase,status,checkpoint_id,issue_id,summary,evidence,updated_by,updated_at) " +
                "values(?,?,?,?,?,?,?,?,?,?)"
        )) {
            for (Map<String, String> row : rows) {
                for (int i = 0; i < GOVERNANCE_COLUMNS.size(); i++) {
                    ps.setString(i + 1, row.get(GOVERNANCE_COLUMNS.get(i)));
                }
                ps.setString(10, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static void syncVersionControlState(Connection conn) throws Exception {
        List<String> trackedFiles = gitLines("git", "ls-files");
        Map<String, String> statusByPath = gitStatusByPath();
        String gitHead = gitSingle("git", "rev-parse", "HEAD");
        String gitBranch = gitSingle("git", "rev-parse", "--abbrev-ref", "HEAD");

        int modifiedCount = 0;
        int untrackedCount = 0;
        int deletedCount = 0;
        for (String code : statusByPath.values()) {
            String normalized = text(code);
            if (normalized.startsWith("??")) {
                untrackedCount++;
            }
            if (normalized.contains("D")) {
                deletedCount++;
            }
            if (!normalized.isBlank() && !normalized.equals("??")) {
                modifiedCount++;
            }
        }
        boolean isDirty = !statusByPath.isEmpty();
        Set<String> allPaths = new LinkedHashSet<>(trackedFiles);
        allPaths.addAll(statusByPath.keySet());

        String now = nowIso();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
            "insert into repo_vcs_snapshot(id,git_head,git_branch,is_dirty,tracked_count,modified_count,untracked_count,deleted_count,updated_at) " +
                "values(1,?,?,?,?,?,?,?,?) " +
                "on conflict(id) do update set " +
                "git_head=excluded.git_head, git_branch=excluded.git_branch, is_dirty=excluded.is_dirty, " +
                "tracked_count=excluded.tracked_count, modified_count=excluded.modified_count, " +
                "untracked_count=excluded.untracked_count, deleted_count=excluded.deleted_count, updated_at=excluded.updated_at"
        )) {
            ps.setString(1, gitHead);
            ps.setString(2, gitBranch);
            ps.setInt(3, isDirty ? 1 : 0);
            ps.setInt(4, trackedFiles.size());
            ps.setInt(5, modifiedCount);
            ps.setInt(6, untrackedCount);
            ps.setInt(7, deletedCount);
            ps.setString(8, now);
            ps.executeUpdate();
        }

        try (Statement clear = conn.createStatement()) {
            clear.execute("delete from repo_file_state");
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "insert into repo_file_state(path,tracked,status_code,exists_on_disk,size_bytes,sha256,updated_at) values(?,?,?,?,?,?,?)"
        )) {
            Set<String> trackedSet = new LinkedHashSet<>(trackedFiles);
            for (String path : allPaths) {
                Path p = Path.of(path);
                boolean exists = Files.exists(p);
                long sizeBytes = exists ? safeSize(p) : 0L;
                String sha = exists && Files.isRegularFile(p) ? safeSha256(p) : "";
                ps.setString(1, path.replace('\\', '/'));
                ps.setInt(2, trackedSet.contains(path) ? 1 : 0);
                ps.setString(3, statusByPath.getOrDefault(path, ""));
                ps.setInt(4, exists ? 1 : 0);
                ps.setLong(5, sizeBytes);
                ps.setString(6, sha);
                ps.setString(7, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static void syncBoilerplatePackages(Connection conn, Path packagesDir) throws Exception {
        String now = nowIso();
        List<CandidateRow> candidates = scanCandidates(packagesDir);

        conn.setAutoCommit(false);
        try (Statement clear = conn.createStatement()) {
            clear.execute("delete from package_files");
            clear.execute("delete from package_candidates");
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "insert into package_candidates(candidate_id,package_id,target_repo,source_repo,created_at,summary,package_zip,patch_count,manifest_file_count,supersedes_json,updated_at) " +
                "values(?,?,?,?,?,?,?,?,?,?,?)"
        )) {
            for (CandidateRow row : candidates) {
                ps.setString(1, row.candidateId());
                ps.setString(2, row.packageId());
                ps.setString(3, row.targetRepo());
                ps.setString(4, row.sourceRepo());
                ps.setString(5, row.createdAt());
                ps.setString(6, row.summary());
                ps.setString(7, row.packageZip());
                ps.setInt(8, row.patchCount());
                ps.setInt(9, row.manifestFileCount());
                ps.setString(10, GSON.toJson(row.supersedes()));
                ps.setString(11, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
            "insert into package_files(candidate_id,file_path,size_bytes,updated_at) values(?,?,?,?)"
        )) {
            for (CandidateRow row : candidates) {
                for (PackageFileRow file : row.files()) {
                    ps.setString(1, row.candidateId());
                    ps.setString(2, file.path());
                    ps.setLong(3, file.sizeBytes());
                    ps.setString(4, now);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static List<CandidateRow> scanCandidates(Path packagesDir) throws Exception {
        if (packagesDir == null || !Files.exists(packagesDir) || !Files.isDirectory(packagesDir)) {
            return List.of();
        }
        List<Path> dirs;
        try (var stream = Files.list(packagesDir)) {
            dirs = stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        }
        List<CandidateRow> out = new ArrayList<>();
        for (Path dir : dirs) {
            String candidateId = dir.getFileName().toString();
            String targetRepo = packagesDir.getFileName().toString();
            String created = ISO.format(Files.getLastModifiedTime(dir).toInstant().atOffset(ZoneOffset.UTC));
            Path readme = dir.resolve("README.md");
            Path manifest = dir.resolve("package-manifest.json");
            String summary = readSummary(readme);
            String packageId = readManifestString(manifest, "packageId");
            String sourceRepo = readManifestString(manifest, "sourceRepo");
            String targetFromManifest = readManifestString(manifest, "targetRepo");
            List<String> supersedes = readManifestArray(manifest, "supersedes");
            int patchCount = countFiles(dir.resolve("patches"), ".patch");
            int manifestFileCount = countManifestFiles(manifest);
            List<PackageFileRow> files = listFiles(dir);
            Path zipPath = packagesDir.resolve(candidateId + ".zip");
            String zipRel = Files.exists(zipPath) ? packagesDir.relativize(zipPath).toString().replace('\\', '/') : "";
            out.add(new CandidateRow(
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
            ));
        }
        return out;
    }

    private static List<Map<String, String>> readCsv(Path csvPath, List<String> requiredColumns) throws Exception {
        if (csvPath == null || !Files.exists(csvPath)) {
            throw new IllegalArgumentException("Missing CSV: " + csvPath);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .build();
        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            List<String> headers = parser.getHeaderNames();
            for (String required : requiredColumns) {
                if (!headers.contains(required)) {
                    throw new IllegalArgumentException("CSV missing required column: " + required + " in " + csvPath);
                }
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String key : requiredColumns) {
                    row.put(key, Optional.ofNullable(record.get(key)).orElse("").trim());
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static List<String> changedPaths() {
        ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            List<String> lines = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            int exit = process.waitFor();
            if (exit != 0) {
                return List.of();
            }
            List<String> paths = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String raw = line.length() >= 4 ? line.substring(3).trim() : "";
                if (raw.contains(" -> ")) {
                    raw = raw.substring(raw.indexOf(" -> ") + 4).trim();
                }
                if (!raw.isBlank()) {
                    paths.add(raw.replace('\\', '/'));
                }
            }
            return paths;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> gitLines(String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            List<String> lines = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
            int exit = process.waitFor();
            if (exit != 0) {
                return List.of();
            }
            List<String> normalized = new ArrayList<>();
            for (String line : lines) {
                normalized.add(line.replace('\\', '/'));
            }
            return normalized;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String gitSingle(String... command) {
        List<String> lines = gitLines(command);
        return lines.isEmpty() ? "" : lines.get(0);
    }

    private static Map<String, String> gitStatusByPath() {
        Map<String, String> out = new LinkedHashMap<>();
        ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            List<String> lines = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            int exit = process.waitFor();
            if (exit != 0) {
                return out;
            }
            for (String line : lines) {
                if (line == null || line.isBlank() || line.length() < 3) {
                    continue;
                }
                String code = line.substring(0, 2);
                String raw = line.substring(3).trim();
                if (raw.contains(" -> ")) {
                    raw = raw.substring(raw.indexOf(" -> ") + 4).trim();
                }
                if (!raw.isBlank()) {
                    out.put(raw.replace('\\', '/'), code);
                }
            }
            return out;
        } catch (Exception e) {
            return out;
        }
    }

    private static boolean globMatch(String path, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(Path.of(path));
        } catch (Exception e) {
            return path.equals(pattern);
        }
    }

    private static boolean changeCoversTarget(String changePath, String targetPath) {
        String c = trimTrailingSlash(changePath);
        String t = trimTrailingSlash(targetPath);
        if (c.equals(t)) {
            return true;
        }
        return t.startsWith(c + "/") || c.startsWith(t + "/");
    }

    private static String trimTrailingSlash(String in) {
        String s = in == null ? "" : in;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean isOpenStatus(String status) {
        String normalized = text(status).toLowerCase(Locale.ROOT);
        return normalized.equals("open") || normalized.equals("in progress") || normalized.equals("active");
    }

    private static List<String> splitPipe(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : value.split("\\|")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static double parsePercent(String value) {
        String text = text(value).replace("%", "").trim();
        if (text.isEmpty()) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static Double number(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private static Double numberAtPath(JsonObject root, List<String>... paths) {
        for (List<String> path : paths) {
            JsonElement cursor = root;
            boolean ok = true;
            for (String key : path) {
                if (cursor == null || !cursor.isJsonObject()) {
                    ok = false;
                    break;
                }
                JsonObject asObject = cursor.getAsJsonObject();
                if (!asObject.has(key) || asObject.get(key).isJsonNull()) {
                    ok = false;
                    break;
                }
                cursor = asObject.get(key);
            }
            if (ok && cursor != null) {
                try {
                    return cursor.getAsDouble();
                } catch (Exception ignored) {
                    // Continue trying alternate paths.
                }
            }
        }
        return null;
    }

    private static Double numberOrNull(ResultSet rs, int index) throws SQLException {
        double value = rs.getDouble(index);
        return rs.wasNull() ? null : value;
    }

    private static void bindNumber(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static JsonObject readJsonObject(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return new JsonObject();
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        try {
            JsonElement parsed = GSON.fromJson(text, JsonElement.class);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            return new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static Object readArrayOrEmpty(JsonObject object, String key) {
        if (object != null && object.has(key) && object.get(key).isJsonArray()) {
            return GSON.fromJson(object.get(key), List.class);
        }
        return List.of();
    }

    private static void writeJson(Path path, Object payload) throws IOException {
        ensureParent(path);
        Files.writeString(path, GSON.toJson(payload) + "\n", StandardCharsets.UTF_8);
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0).format(ISO);
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String safeSha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String readSummary(Path readme) throws IOException {
        if (!Files.exists(readme)) {
            return "";
        }
        List<String> lines = Files.readAllLines(readme, StandardCharsets.UTF_8);
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
        }
        return "";
    }

    private static String readManifestString(Path manifest, String key) throws IOException {
        JsonObject object = readJsonObject(manifest);
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> readManifestArray(Path manifest, String key) throws IOException {
        JsonObject object = readJsonObject(manifest);
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement e : object.get(key).getAsJsonArray()) {
            try {
                String s = e.getAsString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            } catch (Exception ignored) {
                // Skip malformed entries.
            }
        }
        return out;
    }

    private static int countManifestFiles(Path manifest) throws IOException {
        JsonObject object = readJsonObject(manifest);
        if (!object.has("files") || !object.get("files").isJsonArray()) {
            return 0;
        }
        return object.get("files").getAsJsonArray().size();
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

    private static List<PackageFileRow> listFiles(Path packageDir) throws IOException {
        List<PackageFileRow> out = new ArrayList<>();
        Files.walkFileTree(packageDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = packageDir.relativize(file).toString().replace('\\', '/');
                out.add(new PackageFileRow(rel, attrs.size()));
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparing(PackageFileRow::path));
        return out;
    }

    private record CandidateRow(String candidateId,
                                String packageId,
                                String targetRepo,
                                String sourceRepo,
                                String createdAt,
                                String summary,
                                String packageZip,
                                int patchCount,
                                int manifestFileCount,
                                List<String> supersedes,
                                List<PackageFileRow> files) {}

    private record PackageFileRow(String path, long sizeBytes) {}
}
