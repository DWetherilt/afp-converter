package solutions.pointzero.symphony.pm.tools;

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
    private static final List<String> REQUIRED_DICTIONARY_OBJECTS = List.of(
        "issues",
        "plan_tasks",
        "governance_events",
        "repo_vcs_snapshot",
        "repo_file_state",
        "policy_rule_catalog",
        "pm_data_dictionary",
        "code_files",
        "code_file_content",
        "code_tokens",
        "token_dictionary",
        "pm_workflow_manifest",
        "pm_workflow_phase_task_map",
        "pm_workflow_defaults",
        "package_candidates",
        "package_files"
    );
    private static final List<String> REQUIRED_POLICY_RULE_IDS = List.of(
        "PM-COMMS-001",
        "PM-SQLCODE-001",
        "PM-SQLCODE-002",
        "PM-SQLCODE-003",
        "PM-SQLCODE-004",
        "PM-SQLCODE-005",
        "PM-SQLCODE-006"
    );
    private static final Set<String> CODE_INDEX_EXTENSIONS = Set.of(
        ".java", ".kt", ".groovy", ".gradle", ".kts", ".xml", ".json", ".yaml", ".yml",
        ".properties", ".sql", ".py", ".sh", ".bat", ".md", ".txt", ".csv"
    );
    private static final int MAX_CODE_FILE_BYTES = 2_000_000;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private StateDatabaseTool() {}

    public static void main(String[] args) throws Exception {
        int exit = execute(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int execute(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing command. Use: sync-project|sync-boilerplate|sync-policy-rules|export-progress-json|issues-tickle|issues-effectiveness|governance-alerts|version-control-ledger|export-project-file-inventory|export-boilerplate-package-inventory|export-boilerplate-promotion-report|export-policy-rules|export-data-dictionary|lint-data-dictionary|lint-policy-rules|sync-code-index|search-code-token|search-code-token-multi|materialize-code-realm|upsert-code-file|upsert-code-files-manifest|verify-code-realm|report-code-realm-coverage");
        }
        String command = args[0];
        Map<String, String> cli = parseArgs(args, 1);
        return switch (command) {
            case "sync-project" -> runSyncProject(cli);
            case "sync-boilerplate" -> runSyncBoilerplate(cli);
            case "sync-policy-rules" -> runSyncPolicyRules(cli);
            case "export-progress-json" -> runExportProgressJson(cli);
            case "issues-tickle" -> runIssuesTickle(cli);
            case "issues-effectiveness" -> runIssuesEffectiveness(cli);
            case "governance-alerts" -> runGovernanceAlerts(cli);
            case "version-control-ledger" -> runVersionControlLedger(cli);
            case "export-project-file-inventory" -> runExportProjectFileInventory(cli);
            case "export-boilerplate-package-inventory" -> runExportBoilerplatePackageInventory(cli);
            case "export-boilerplate-promotion-report" -> runExportBoilerplatePromotionReport(cli);
            case "export-policy-rules" -> runExportPolicyRules(cli);
            case "export-data-dictionary" -> runExportDataDictionary(cli);
            case "lint-data-dictionary" -> runLintDataDictionary(cli);
            case "lint-policy-rules" -> runLintPolicyRules(cli);
            case "sync-code-index" -> runSyncCodeIndex(cli);
            case "search-code-token" -> runSearchCodeToken(cli);
            case "search-code-token-multi" -> runSearchCodeTokenMulti(cli);
            case "materialize-code-realm" -> runMaterializeCodeRealm(cli);
            case "upsert-code-file" -> runUpsertCodeFile(cli);
            case "upsert-code-files-manifest" -> runUpsertCodeFilesManifest(cli);
            case "verify-code-realm" -> runVerifyCodeRealm(cli);
            case "report-code-realm-coverage" -> runReportCodeRealmCoverage(cli);
            default -> throw new IllegalArgumentException("Unsupported command: " + command);
        };
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
        Path policySqlPath = optionalPath(cli, "--policy-sql");
        ensureParent(dbPath);
        try (Connection conn = connect(dbPath)) {
            initProjectSchema(conn);
            syncIssues(conn, issuesPath);
            syncProgress(conn, progressPath);
            syncFidelity(conn, fidelityPath);
            syncGovernanceEvents(conn, governanceEventsPath);
            syncPolicyRules(conn, policySqlPath);
            syncVersionControlState(conn);
        }
        return 0;
    }

    private static int runSyncPolicyRules(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path policySqlPath = requiredPath(cli, "--sql");
        ensureParent(dbPath);
        try (Connection conn = connect(dbPath)) {
            initProjectSchema(conn);
            syncPolicyRules(conn, policySqlPath);
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
        Path sqlDriftPath = optionalPath(cli, "--sql-drift");
        boolean enforce = "true".equalsIgnoreCase(cli.getOrDefault("--enforce", "false"));
        ensureParent(outputPath);

        List<Map<String, String>> activeBreaches = new ArrayList<>();
        List<Map<String, String>> trustEntries = new ArrayList<>();
        List<Map<String, String>> policyGovernanceEntries = new ArrayList<>();
        int enabledPolicyRuleCount = 0;
        int requiredPolicyRuleEnabledCount = 0;
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
                    if (summary.contains("policy")
                        || phase.contains("policy")
                        || event.get("checkpoint_id").toLowerCase(Locale.ROOT).contains("policy")) {
                        policyGovernanceEntries.add(event);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "select count(*) from policy_rule_catalog where enabled = 1"
            ); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    enabledPolicyRuleCount = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "select count(*) from policy_rule_catalog where enabled = 1 and rule_id = 'PM-COMMS-001'"
            ); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    requiredPolicyRuleEnabledCount = rs.getInt(1);
                }
            }
        }

        boolean hasBreach = !activeBreaches.isEmpty();
        boolean requiredPolicyRuleMissing = requiredPolicyRuleEnabledCount == 0;
        boolean sqlAuthorityDrift = false;
        JsonObject sqlDrift = null;
        if (sqlDriftPath != null && Files.exists(sqlDriftPath)) {
            sqlDrift = readJsonObject(sqlDriftPath);
            sqlAuthorityDrift = sqlDrift.has("ok") && !sqlDrift.get("ok").getAsBoolean();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("hasActiveGovernanceBreach", hasBreach);
        payload.put("activeBreachCount", activeBreaches.size());
        payload.put("trustEventCount", trustEntries.size());
        payload.put("policyGovernanceEventCount", policyGovernanceEntries.size());
        payload.put("activeBreaches", activeBreaches);
        payload.put("trustEvents", trustEntries);
        payload.put("policyGovernanceEvents", policyGovernanceEntries);
        payload.put("policyRuleAudit", Map.of(
            "enabledPolicyRuleCount", enabledPolicyRuleCount,
            "requiredRuleId", "PM-COMMS-001",
            "requiredRuleEnabled", requiredPolicyRuleEnabledCount > 0
        ));
        payload.put("sqlAuthorityAudit", Map.of(
            "driftReportPath", sqlDriftPath == null ? "" : sqlDriftPath.toString().replace('\\', '/'),
            "driftDetected", sqlAuthorityDrift
        ));
        if (sqlDrift != null) {
            payload.put("sqlAuthorityDrift", sqlDrift);
        }
        payload.put("message", hasBreach
            ? "Governance breach/in-progress items require human visibility."
            : (requiredPolicyRuleMissing
                ? "No active governance breaches, but required policy rules are missing/disabled."
                : (sqlAuthorityDrift
                    ? "No active governance breaches, but SQL authority drift is detected."
                    : "No active governance breaches.")));
        writeJson(outputPath, payload);
        return (enforce && (hasBreach || requiredPolicyRuleMissing || sqlAuthorityDrift)) ? 2 : 0;
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

    private static int runExportProjectFileInventory(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path csvPath = requiredPath(cli, "--csv");
        ensureParent(csvPath);

        String[] header = {
            "path",
            "tracked",
            "status_code",
            "exists_on_disk",
            "size_bytes",
            "sha256",
            "source_table",
            "generated_by",
            "reporting_task",
            "maintenance_context"
        };
        List<String[]> rows = new ArrayList<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select path, tracked, status_code, exists_on_disk, size_bytes, sha256 " +
                     "from repo_file_state order by path asc"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[] {
                    text(rs.getString(1)),
                    Integer.toString(rs.getInt(2)),
                    text(rs.getString(3)),
                    Integer.toString(rs.getInt(4)),
                    Long.toString(rs.getLong(5)),
                    text(rs.getString(6)),
                    "repo_file_state",
                    "projectStateDb (sync-project -> syncVersionControlState)",
                    "versionControlLedger",
                    "Rebuilt from git ls-files + git status on each projectStateDb run"
                });
            }
        }
        writeCsv(csvPath, header, rows);
        return 0;
    }

    private static int runExportBoilerplatePackageInventory(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path csvPath = requiredPath(cli, "--csv");
        ensureParent(csvPath);

        String[] header = {
            "realm",
            "candidate_id",
            "package_id",
            "target_repo",
            "promotion_status",
            "promoted_at",
            "file_path",
            "size_bytes",
            "source_table",
            "generated_by",
            "reporting_task",
            "maintenance_context"
        };
        List<String[]> rows = new ArrayList<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select pc.realm, pf.candidate_id, pc.package_id, pc.target_repo, pc.promotion_status, pc.promoted_at, pf.file_path, pf.size_bytes " +
                     "from package_files pf " +
                     "join package_candidates pc on pc.candidate_id = pf.candidate_id " +
                     "order by pf.candidate_id asc, pf.file_path asc"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[] {
                    text(rs.getString(1)),
                    text(rs.getString(2)),
                    text(rs.getString(3)),
                    text(rs.getString(4)),
                    text(rs.getString(5)),
                    text(rs.getString(6)),
                    text(rs.getString(7)),
                    Long.toString(rs.getLong(8)),
                    "package_files",
                    "boilerplateStateDb (sync-boilerplate)",
                    "boilerplateSyncWorkbook",
                    "Rebuilt from boilerplate/update-packages manifests + package trees on each boilerplateStateDb run"
                });
            }
        }
        writeCsv(csvPath, header, rows);
        return 0;
    }

    private static int runExportBoilerplatePromotionReport(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path jsonPath = requiredPath(cli, "--json");
        ensureParent(jsonPath);

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select candidate_id, package_id, target_repo, source_repo, created_at, package_zip, patch_count, manifest_file_count, " +
                     "promotion_status, promoted_at, promotion_notes, supersedes_json " +
                     "from package_candidates order by candidate_id asc"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String status = normalizePromotionStatus(text(rs.getString(9)));
                statusCounts.put(status, statusCounts.getOrDefault(status, 0) + 1);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("candidateId", text(rs.getString(1)));
                row.put("packageId", text(rs.getString(2)));
                row.put("targetRepo", text(rs.getString(3)));
                row.put("sourceRepo", text(rs.getString(4)));
                row.put("createdAt", text(rs.getString(5)));
                row.put("packageZip", text(rs.getString(6)));
                row.put("patchCount", rs.getInt(7));
                row.put("manifestFileCount", rs.getInt(8));
                row.put("promotionStatus", status);
                row.put("promotedAt", text(rs.getString(10)));
                row.put("promotionNotes", text(rs.getString(11)));
                row.put("supersedes", parseJsonStringArray(text(rs.getString(12))));
                row.put("recommendedAction", recommendedActionForStatus(status));
                rows.add(row);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("sourceTable", "package_candidates");
        payload.put("candidateCount", rows.size());
        payload.put("statusCounts", statusCounts);
        payload.put("candidates", rows);
        writeJson(jsonPath, payload);
        return 0;
    }

    private static int runExportPolicyRules(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path jsonPath = requiredPath(cli, "--json");
        ensureParent(jsonPath);

        List<Map<String, Object>> rules = new ArrayList<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement dictPs = conn.prepareStatement(
                 "select object_name, source_ref from pm_data_dictionary"
             );
             ResultSet dictRs = dictPs.executeQuery();
             PreparedStatement ps = conn.prepareStatement(
                 "select rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at " +
                     "from policy_rule_catalog order by realm asc, category asc, rule_id asc"
             );
             ResultSet rs = ps.executeQuery()) {
            List<DictionaryRef> dictionaryRefs = new ArrayList<>();
            while (dictRs.next()) {
                dictionaryRefs.add(new DictionaryRef(
                    text(dictRs.getString(1)),
                    text(dictRs.getString(2))
                ));
            }
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                String ruleText = text(rs.getString(4));
                String sourceRef = text(rs.getString(5));
                row.put("ruleId", text(rs.getString(1)));
                row.put("realm", text(rs.getString(2)));
                row.put("category", text(rs.getString(3)));
                row.put("ruleText", ruleText);
                row.put("sourceRef", sourceRef);
                row.put("mutableBy", text(rs.getString(6)));
                row.put("enabled", rs.getInt(7) != 0);
                row.put("updatedAt", text(rs.getString(8)));
                row.put("dictionaryRefs", resolveDictionaryRefs(ruleText, sourceRef, dictionaryRefs));
                rules.add(row);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("sourceTable", "policy_rule_catalog");
        payload.put("ruleCount", rules.size());
        payload.put("rules", rules);
        writeJson(jsonPath, payload);
        return 0;
    }

    private static int runExportDataDictionary(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path jsonPath = requiredPath(cli, "--json");
        ensureParent(jsonPath);

        List<Map<String, Object>> entries = new ArrayList<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select object_name, object_type, realm, definition, source_ref, naming_pattern, updated_at " +
                     "from pm_data_dictionary order by realm asc, object_type asc, object_name asc"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("objectName", text(rs.getString(1)));
                row.put("objectType", text(rs.getString(2)));
                row.put("realm", text(rs.getString(3)));
                row.put("definition", text(rs.getString(4)));
                row.put("sourceRef", text(rs.getString(5)));
                row.put("namingPattern", text(rs.getString(6)));
                row.put("updatedAt", text(rs.getString(7)));
                entries.add(row);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("sourceTable", "pm_data_dictionary");
        payload.put("entryCount", entries.size());
        payload.put("entries", entries);
        writeJson(jsonPath, payload);
        return 0;
    }

    private static int runLintDataDictionary(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path outputPath = requiredPath(cli, "--output");
        ensureParent(outputPath);

        List<String> required = parseRequiredDictionaryObjects(cli.get("--required"));
        Set<String> present = new LinkedHashSet<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement("select object_name from pm_data_dictionary");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = text(rs.getString(1));
                if (!name.isBlank()) {
                    present.add(name);
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (!present.contains(key)) {
                missing.add(key);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("sourceTable", "pm_data_dictionary");
        payload.put("requiredCount", required.size());
        payload.put("presentCount", present.size());
        payload.put("missingCount", missing.size());
        payload.put("requiredObjects", required);
        payload.put("missingObjects", missing);
        payload.put("ok", missing.isEmpty());
        payload.put("message", missing.isEmpty()
            ? "Data dictionary lint passed."
            : "Data dictionary lint failed: missing required objects.");
        writeJson(outputPath, payload);
        return missing.isEmpty() ? 0 : 2;
    }

    private static int runLintPolicyRules(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path outputPath = requiredPath(cli, "--output");
        ensureParent(outputPath);

        List<String> required = parseRequiredRuleIds(cli.get("--required"));
        Set<String> enabledRules = new LinkedHashSet<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select rule_id from policy_rule_catalog where enabled = 1"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String ruleId = text(rs.getString(1));
                if (!ruleId.isBlank()) {
                    enabledRules.add(ruleId);
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (String ruleId : required) {
            if (!enabledRules.contains(ruleId)) {
                missing.add(ruleId);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("sourceTable", "policy_rule_catalog");
        payload.put("requiredCount", required.size());
        payload.put("enabledCount", enabledRules.size());
        payload.put("missingCount", missing.size());
        payload.put("requiredRuleIds", required);
        payload.put("missingRuleIds", missing);
        payload.put("ok", missing.isEmpty());
        payload.put("message", missing.isEmpty()
            ? "Policy-rule lint passed."
            : "Policy-rule lint failed: required rules missing/disabled.");
        writeJson(outputPath, payload);
        return missing.isEmpty() ? 0 : 2;
    }

    private static List<String> parseRequiredDictionaryObjects(String raw) {
        if (raw == null || raw.isBlank()) {
            return REQUIRED_DICTIONARY_OBJECTS;
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String key = text(part).trim();
            if (!key.isBlank()) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return REQUIRED_DICTIONARY_OBJECTS;
        }
        return List.copyOf(keys);
    }

    private static List<String> parseRequiredRuleIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return REQUIRED_POLICY_RULE_IDS;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String id = text(part).trim();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return REQUIRED_POLICY_RULE_IDS;
        }
        return List.copyOf(ids);
    }

    private static int runSyncCodeIndex(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        String realm = requiredValue(cli, "--realm");
        List<Path> roots = parseRootPaths(cli.get("--roots"));
        ensureParent(dbPath);
        try (Connection conn = connect(dbPath)) {
            initCodeIndexSchema(conn);
            clearCodeIndex(conn, realm);
            syncCodeIndex(conn, realm, roots);
            rebuildTokenDictionary(conn, realm);
        }
        return 0;
    }

    private static int runSearchCodeToken(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        Path outputPath = requiredPath(cli, "--output");
        String keyword = text(cli.get("--keyword")).toLowerCase(Locale.ROOT);
        int limit = parsePositiveInt(cli.get("--limit"), 200);
        ensureParent(outputPath);

        List<Map<String, Object>> dictionaryMatches = new ArrayList<>();
        List<Map<String, Object>> fileHits = new ArrayList<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement dictPs = conn.prepareStatement(
                 "select token, file_count, total_occurrences, updated_at " +
                     "from token_dictionary where token like ? order by total_occurrences desc, token asc limit ?"
             );
             PreparedStatement filePs = conn.prepareStatement(
                 "select ct.token, ct.path, ct.occurrences, cf.realm, cf.language, cf.updated_at " +
                     "from code_tokens ct join code_files cf on cf.path = ct.path " +
                     "where ct.token like ? order by ct.occurrences desc, ct.path asc limit ?"
             )) {
            String like = "%" + keyword + "%";
            dictPs.setString(1, like);
            dictPs.setInt(2, limit);
            try (ResultSet rs = dictPs.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("token", text(rs.getString(1)));
                    row.put("fileCount", rs.getInt(2));
                    row.put("totalOccurrences", rs.getInt(3));
                    row.put("updatedAt", text(rs.getString(4)));
                    dictionaryMatches.add(row);
                }
            }

            filePs.setString(1, like);
            filePs.setInt(2, limit);
            try (ResultSet rs = filePs.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("token", text(rs.getString(1)));
                    row.put("path", text(rs.getString(2)));
                    row.put("occurrences", rs.getInt(3));
                    row.put("realm", text(rs.getString(4)));
                    row.put("language", text(rs.getString(5)));
                    row.put("updatedAt", text(rs.getString(6)));
                    fileHits.add(row);
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("query", keyword);
        payload.put("dictionaryMatchCount", dictionaryMatches.size());
        payload.put("fileHitCount", fileHits.size());
        payload.put("dictionaryMatches", dictionaryMatches);
        payload.put("fileHits", fileHits);
        writeJson(outputPath, payload);
        return 0;
    }

    private static int runSearchCodeTokenMulti(Map<String, String> cli) throws Exception {
        String dbsRaw = requiredValue(cli, "--dbs");
        String keyword = text(cli.get("--keyword")).toLowerCase(Locale.ROOT);
        Path outputPath = requiredPath(cli, "--output");
        int limit = parsePositiveInt(cli.get("--limit"), 200);
        ensureParent(outputPath);

        List<Path> dbs = parseRootPaths(dbsRaw);
        Map<String, Integer> aggregateTokenCounts = new LinkedHashMap<>();
        List<Map<String, Object>> fileHits = new ArrayList<>();
        for (Path dbPath : dbs) {
            try (Connection conn = connect(dbPath);
                 PreparedStatement dictPs = conn.prepareStatement(
                     "select token, total_occurrences from token_dictionary where token like ? order by total_occurrences desc limit ?"
                 );
                 PreparedStatement filePs = conn.prepareStatement(
                     "select ct.token, ct.path, ct.occurrences, cf.realm, cf.language " +
                         "from code_tokens ct join code_files cf on cf.path = ct.path " +
                         "where ct.token like ? order by ct.occurrences desc limit ?"
                 )) {
                String like = "%" + keyword + "%";
                dictPs.setString(1, like);
                dictPs.setInt(2, limit);
                try (ResultSet rs = dictPs.executeQuery()) {
                    while (rs.next()) {
                        aggregateTokenCounts.merge(text(rs.getString(1)), rs.getInt(2), Integer::sum);
                    }
                }
                filePs.setString(1, like);
                filePs.setInt(2, limit);
                try (ResultSet rs = filePs.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("db", dbPath.toString().replace('\\', '/'));
                        row.put("token", text(rs.getString(1)));
                        row.put("path", text(rs.getString(2)));
                        row.put("occurrences", rs.getInt(3));
                        row.put("realm", text(rs.getString(4)));
                        row.put("language", text(rs.getString(5)));
                        fileHits.add(row);
                    }
                }
            }
        }

        List<Map<String, Object>> dictionaryMatches = aggregateTokenCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("token", e.getKey());
                row.put("totalOccurrences", e.getValue());
                return row;
            })
            .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("source", "sqlite");
        payload.put("query", keyword);
        payload.put("databaseCount", dbs.size());
        payload.put("dictionaryMatchCount", dictionaryMatches.size());
        payload.put("fileHitCount", fileHits.size());
        payload.put("dictionaryMatches", dictionaryMatches);
        payload.put("fileHits", fileHits);
        writeJson(outputPath, payload);
        return 0;
    }

    private static int runMaterializeCodeRealm(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        String realm = requiredValue(cli, "--realm");
        Path targetRoot = requiredPath(cli, "--target-root");
        ensureParent(targetRoot.resolve(".placeholder"));
        int written = 0;
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select path, content, encoding from code_file_content where realm = ? order by path asc"
             )) {
            initCodeIndexSchema(conn);
            ps.setString(1, realm);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String endpoint = text(rs.getString(1));
                    String content = rs.getString(2);
                    String encoding = text(rs.getString(3));
                    if (endpoint.isBlank()) {
                        continue;
                    }
                    Path out = safeMaterializePath(targetRoot, endpoint);
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    if ("utf-8".equalsIgnoreCase(encoding) || encoding.isBlank()) {
                        Files.writeString(out, content == null ? "" : content, StandardCharsets.UTF_8);
                    } else {
                        Files.writeString(out, content == null ? "" : content, StandardCharsets.UTF_8);
                    }
                    written++;
                }
            }
        }
        return written >= 0 ? 0 : 1;
    }

    private static int runUpsertCodeFile(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        String realm = requiredValue(cli, "--realm");
        String endpoint = requiredValue(cli, "--endpoint");
        Path source = requiredPath(cli, "--source");
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Missing source file: " + source);
        }
        ensureParent(dbPath);
        try (Connection conn = connect(dbPath)) {
            initCodeIndexSchema(conn);
            upsertCodeFileFromSource(conn, realm, endpoint, source);
            rebuildTokenDictionary(conn, realm);
        }
        return 0;
    }

    private static int runUpsertCodeFilesManifest(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        String realm = requiredValue(cli, "--realm");
        Path manifest = requiredPath(cli, "--manifest");
        if (!Files.exists(manifest)) {
            throw new IllegalArgumentException("Missing manifest file: " + manifest);
        }
        JsonObject root = readJsonObject(manifest);
        JsonElement entries = root.get("files");
        if (entries == null || !entries.isJsonArray()) {
            throw new IllegalArgumentException("Manifest must contain files[] array: " + manifest);
        }
        ensureParent(dbPath);
        int count = 0;
        try (Connection conn = connect(dbPath)) {
            initCodeIndexSchema(conn);
            conn.setAutoCommit(false);
            try {
                for (JsonElement el : entries.getAsJsonArray()) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    String endpoint = text(row.has("endpoint") ? row.get("endpoint").getAsString() : "");
                    String sourceRaw = text(row.has("source") ? row.get("source").getAsString() : "");
                    if (endpoint.isBlank() || sourceRaw.isBlank()) {
                        continue;
                    }
                    Path source = Path.of(sourceRaw);
                    upsertCodeFileFromSource(conn, realm, endpoint, source);
                    count++;
                }
                rebuildTokenDictionary(conn, realm);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return count >= 0 ? 0 : 1;
    }

    private static int runVerifyCodeRealm(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        String realm = requiredValue(cli, "--realm");
        Path targetRoot = requiredPath(cli, "--target-root");
        Path outputPath = requiredPath(cli, "--output");
        boolean enforce = "true".equalsIgnoreCase(cli.getOrDefault("--enforce", "false"));
        ensureParent(outputPath);

        List<Map<String, Object>> mismatches = new ArrayList<>();
        int tracked = 0;
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select path, content_sha256 from code_file_content where realm = ? order by path asc"
             )) {
            initCodeIndexSchema(conn);
            ps.setString(1, realm);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tracked++;
                    String endpoint = text(rs.getString(1));
                    String expectedHash = text(rs.getString(2));
                    Path out = safeMaterializePath(targetRoot, endpoint);
                    String actualHash = Files.exists(out) && Files.isRegularFile(out) ? safeSha256(out) : "";
                    if (!expectedHash.equalsIgnoreCase(actualHash)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("path", endpoint);
                        row.put("expectedSha256", expectedHash);
                        row.put("actualSha256", actualHash);
                        row.put("existsOnDisk", Files.exists(out));
                        mismatches.add(row);
                    }
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("realm", realm);
        payload.put("trackedFileCount", tracked);
        payload.put("mismatchCount", mismatches.size());
        payload.put("ok", mismatches.isEmpty());
        payload.put("mismatches", mismatches);
        payload.put("message", mismatches.isEmpty()
            ? "SQL authority verification passed."
            : "SQL authority verification failed: filesystem drift detected.");
        writeJson(outputPath, payload);
        return (enforce && !mismatches.isEmpty()) ? 2 : 0;
    }

    private static int runReportCodeRealmCoverage(Map<String, String> cli) throws Exception {
        Path dbPath = requiredPath(cli, "--db");
        String realm = requiredValue(cli, "--realm");
        List<Path> roots = parseRootPaths(cli.get("--roots"));
        Path outputPath = requiredPath(cli, "--output");
        ensureParent(outputPath);

        Set<String> expected = new LinkedHashSet<>();
        for (Path root : roots) {
            if (root == null || !Files.exists(root)) {
                continue;
            }
            if (Files.isRegularFile(root)) {
                String rel = root.toString().replace('\\', '/');
                if (isManagedCodeEndpoint(rel)) {
                    expected.add(rel);
                }
                continue;
            }
            try (var stream = Files.walk(root)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    String rel = p.toString().replace('\\', '/');
                    if (isManagedCodeEndpoint(rel)) {
                        expected.add(rel);
                    }
                }
            }
        }

        Set<String> indexed = new LinkedHashSet<>();
        try (Connection conn = connect(dbPath);
             PreparedStatement ps = conn.prepareStatement(
                 "select path from code_file_content where realm = ? order by path asc"
             )) {
            ps.setString(1, realm);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String path = text(rs.getString(1));
                    if (!path.isBlank()) {
                        indexed.add(path);
                    }
                }
            }
        }

        List<String> missing = expected.stream().filter(p -> !indexed.contains(p)).toList();
        List<String> extra = indexed.stream().filter(p -> !expected.contains(p)).toList();
        double coverage = expected.isEmpty() ? 1.0 : (expected.size() - missing.size()) / (double) expected.size();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1");
        payload.put("generatedAt", nowIso());
        payload.put("realm", realm);
        payload.put("expectedManagedFileCount", expected.size());
        payload.put("indexedManagedFileCount", indexed.size());
        payload.put("missingCount", missing.size());
        payload.put("extraCount", extra.size());
        payload.put("coverageRatio", round6(coverage));
        payload.put("missingFiles", missing);
        payload.put("extraFiles", extra);
        payload.put("ok", missing.isEmpty());
        payload.put("message", missing.isEmpty()
            ? "Realm code coverage is complete."
            : "Realm code coverage is incomplete.");
        writeJson(outputPath, payload);
        return 0;
    }

    private static void initCodeIndexSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("pragma journal_mode = wal");
            st.execute("""
                create table if not exists code_files (
                    path text primary key,
                    realm text not null default '',
                    language text not null default '',
                    size_bytes integer not null default 0,
                    sha256 text not null default '',
                    updated_at text not null
                )
                """);
            st.execute("""
                create table if not exists code_tokens (
                    token text not null,
                    path text not null,
                    occurrences integer not null default 0,
                    updated_at text not null,
                    primary key(token, path)
                )
                """);
            st.execute("create index if not exists idx_code_tokens_token on code_tokens(token)");
            st.execute("create index if not exists idx_code_tokens_path on code_tokens(path)");
            st.execute("""
                create table if not exists code_file_content (
                    path text primary key,
                    realm text not null default '',
                    encoding text not null default 'utf-8',
                    content text not null default '',
                    content_sha256 text not null default '',
                    updated_at text not null
                )
                """);
            st.execute("create index if not exists idx_code_file_content_realm on code_file_content(realm)");
            st.execute("""
                create table if not exists token_dictionary (
                    token text primary key,
                    realm text not null default '',
                    file_count integer not null default 0,
                    total_occurrences integer not null default 0,
                    token_kind text not null default 'derived',
                    notes text not null default '',
                    updated_at text not null
                )
                """);
            st.execute("create index if not exists idx_token_dictionary_realm on token_dictionary(realm)");
        }
    }

    private static void clearCodeIndex(Connection conn, String realm) throws SQLException {
        try (PreparedStatement delFiles = conn.prepareStatement("delete from code_files where realm = ?");
             PreparedStatement delTokens = conn.prepareStatement(
                 "delete from code_tokens where path in (select path from code_files where realm = ?)"
             );
             PreparedStatement delContent = conn.prepareStatement("delete from code_file_content where realm = ?");
             PreparedStatement delDictionary = conn.prepareStatement("delete from token_dictionary where realm = ?")) {
            delTokens.setString(1, realm);
            delTokens.executeUpdate();
            delFiles.setString(1, realm);
            delFiles.executeUpdate();
            delContent.setString(1, realm);
            delContent.executeUpdate();
            delDictionary.setString(1, realm);
            delDictionary.executeUpdate();
        }
    }

    private static void syncCodeIndex(Connection conn, String realm, List<Path> roots) throws Exception {
        String now = nowIso();
        try (PreparedStatement filePs = conn.prepareStatement(
            "insert or replace into code_files(path, realm, language, size_bytes, sha256, updated_at) values(?,?,?,?,?,?)"
        );
             PreparedStatement tokenPs = conn.prepareStatement(
                 "insert or replace into code_tokens(token, path, occurrences, updated_at) values(?,?,?,?)"
             );
             PreparedStatement contentPs = conn.prepareStatement(
                 "insert or replace into code_file_content(path, realm, encoding, content, content_sha256, updated_at) values(?,?,?,?,?,?)"
             )) {
            for (Path root : roots) {
                if (root == null || !Files.exists(root)) {
                    continue;
                }
                if (Files.isRegularFile(root)) {
                    indexCodeFile(root, realm, now, filePs, tokenPs, contentPs);
                    continue;
                }
                try (var stream = Files.walk(root)) {
                    for (Path path : (Iterable<Path>) stream::iterator) {
                        if (!Files.isRegularFile(path)) {
                            continue;
                        }
                        indexCodeFile(path, realm, now, filePs, tokenPs, contentPs);
                    }
                }
            }
        }
    }

    private static void indexCodeFile(Path file,
                                      String realm,
                                      String now,
                                      PreparedStatement filePs,
                                      PreparedStatement tokenPs,
                                      PreparedStatement contentPs) throws Exception {
        String path = file.toString().replace('\\', '/');
        String ext = extension(path);
        if (!CODE_INDEX_EXTENSIONS.contains(ext)) {
            return;
        }
        long size = safeSize(file);
        if (size <= 0 || size > MAX_CODE_FILE_BYTES) {
            return;
        }
        String language = detectLanguage(path, ext);
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        Map<String, Integer> tokenCounts = extractTokenCounts(content);
        if (tokenCounts.isEmpty()) {
            return;
        }

        filePs.setString(1, path);
        filePs.setString(2, realm);
        filePs.setString(3, language);
        filePs.setLong(4, size);
        filePs.setString(5, safeSha256(file));
        filePs.setString(6, now);
        filePs.executeUpdate();

        contentPs.setString(1, path);
        contentPs.setString(2, realm);
        contentPs.setString(3, "utf-8");
        contentPs.setString(4, content);
        contentPs.setString(5, safeSha256Text(content));
        contentPs.setString(6, now);
        contentPs.executeUpdate();

        for (Map.Entry<String, Integer> entry : tokenCounts.entrySet()) {
            String token = entry.getKey();
            int occurrences = entry.getValue();
            tokenPs.setString(1, token);
            tokenPs.setString(2, path);
            tokenPs.setInt(3, occurrences);
            tokenPs.setString(4, now);
            tokenPs.executeUpdate();
        }
    }

    private static void upsertCodeFileFromSource(Connection conn,
                                                 String realm,
                                                 String endpoint,
                                                 Path source) throws Exception {
        String normalizedEndpoint = endpoint.replace('\\', '/');
        String ext = extension(normalizedEndpoint);
        if (!CODE_INDEX_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported endpoint extension for SQL code management: " + ext);
        }
        String content = Files.readString(source, StandardCharsets.UTF_8);
        String now = nowIso();
        Map<String, Integer> tokenCounts = extractTokenCounts(content);
        try (PreparedStatement filePs = conn.prepareStatement(
            "insert or replace into code_files(path, realm, language, size_bytes, sha256, updated_at) values(?,?,?,?,?,?)"
        );
             PreparedStatement contentPs = conn.prepareStatement(
                 "insert or replace into code_file_content(path, realm, encoding, content, content_sha256, updated_at) values(?,?,?,?,?,?)"
             );
             PreparedStatement delTokens = conn.prepareStatement("delete from code_tokens where path = ?");
             PreparedStatement tokenPs = conn.prepareStatement(
                 "insert or replace into code_tokens(token, path, occurrences, updated_at) values(?,?,?,?)"
             )) {
            filePs.setString(1, normalizedEndpoint);
            filePs.setString(2, realm);
            filePs.setString(3, detectLanguage(normalizedEndpoint, ext));
            filePs.setLong(4, Files.size(source));
            filePs.setString(5, safeSha256(source));
            filePs.setString(6, now);
            filePs.executeUpdate();

            contentPs.setString(1, normalizedEndpoint);
            contentPs.setString(2, realm);
            contentPs.setString(3, "utf-8");
            contentPs.setString(4, content);
            contentPs.setString(5, safeSha256Text(content));
            contentPs.setString(6, now);
            contentPs.executeUpdate();

            delTokens.setString(1, normalizedEndpoint);
            delTokens.executeUpdate();
            for (Map.Entry<String, Integer> entry : tokenCounts.entrySet()) {
                tokenPs.setString(1, entry.getKey());
                tokenPs.setString(2, normalizedEndpoint);
                tokenPs.setInt(3, entry.getValue());
                tokenPs.setString(4, now);
                tokenPs.executeUpdate();
            }
        }
    }

    private static void rebuildTokenDictionary(Connection conn, String realm) throws SQLException {
        String now = nowIso();
        try (PreparedStatement delete = conn.prepareStatement("delete from token_dictionary where realm = ?");
             PreparedStatement insert = conn.prepareStatement(
            "insert or replace into token_dictionary(token, realm, file_count, total_occurrences, token_kind, notes, updated_at) " +
                "select token, ?, count(path), sum(occurrences), 'derived', '', ? from code_tokens " +
                "where path in (select path from code_files where realm = ?) group by token"
        )) {
            delete.setString(1, realm);
            delete.executeUpdate();
            insert.setString(1, realm);
            insert.setString(2, now);
            insert.setString(3, realm);
            insert.executeUpdate();
        }
    }

    private static Map<String, Integer> extractTokenCounts(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        String normalized = content
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("(?m)//.*$", " ")
            .replaceAll("[^A-Za-z0-9_./\\-]+", " ");
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (String raw : normalized.split("\\s+")) {
            String token = text(raw).toLowerCase(Locale.ROOT);
            if (!isIndexableToken(token)) {
                continue;
            }
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    private static boolean isIndexableToken(String token) {
        if (token == null || token.length() < 2 || token.length() > 128) {
            return false;
        }
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                hasLetterOrDigit = true;
                break;
            }
        }
        return hasLetterOrDigit;
    }

    private static String extension(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0 || idx == path.length() - 1) {
            return "";
        }
        return path.substring(idx).toLowerCase(Locale.ROOT);
    }

    private static String detectLanguage(String path, String ext) {
        return switch (ext) {
            case ".java" -> "java";
            case ".kt", ".kts" -> "kotlin";
            case ".gradle", ".groovy" -> "gradle";
            case ".py" -> "python";
            case ".sql" -> "sql";
            case ".md" -> "markdown";
            case ".json" -> "json";
            case ".xml" -> "xml";
            case ".yaml", ".yml" -> "yaml";
            case ".sh" -> "shell";
            case ".bat" -> "batch";
            default -> ext.isBlank() ? "text" : ext.substring(1);
        };
    }

    private static String requiredValue(Map<String, String> cli, String key) {
        String value = text(cli.get(key));
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }

    private static List<Path> parseRootPaths(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: --roots");
        }
        List<Path> paths = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = text(part);
            if (!value.isBlank()) {
                paths.add(Path.of(value));
            }
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Missing required argument: --roots");
        }
        return paths;
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isManagedCodeEndpoint(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String ext = extension(path);
        return CODE_INDEX_EXTENSIONS.contains(ext);
    }

    private static List<String> resolveDictionaryRefs(String ruleText, String sourceRef, List<DictionaryRef> dictionaryRefs) {
        if (dictionaryRefs == null || dictionaryRefs.isEmpty()) {
            return List.of();
        }
        String textBlob = (text(ruleText) + " " + text(sourceRef)).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (DictionaryRef ref : dictionaryRefs) {
            if (ref == null || ref.objectName.isBlank()) {
                continue;
            }
            String objectKey = ref.objectName.toLowerCase(Locale.ROOT);
            String sourceKey = text(ref.sourceRef).toLowerCase(Locale.ROOT);
            if ((!objectKey.isBlank() && textBlob.contains(objectKey))
                || (!sourceKey.isBlank() && textBlob.contains(sourceKey))) {
                refs.add(ref.objectName);
            }
        }
        return List.copyOf(refs);
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
            st.execute("""
                create table if not exists policy_rule_catalog (
                    rule_id text primary key,
                    realm text not null default '',
                    category text not null default '',
                    rule_text text not null default '',
                    source_ref text not null default '',
                    mutable_by text not null default '',
                    enabled integer not null default 1,
                    updated_at text not null
                )
                """);
            st.execute("create index if not exists idx_policy_rule_realm_category on policy_rule_catalog(realm, category)");
        }
    }

    private static void initBoilerplateSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("pragma journal_mode = wal");
            st.execute("""
                create table if not exists package_candidates (
                    candidate_id text primary key,
                    realm text not null default 'boilerplate',
                    package_id text not null default '',
                    target_repo text not null default '',
                    source_repo text not null default '',
                    created_at text not null default '',
                    summary text not null default '',
                    package_zip text not null default '',
                    patch_count integer not null default 0,
                    manifest_file_count integer not null default 0,
                    promotion_status text not null default 'pending_review',
                    promoted_at text not null default '',
                    promotion_notes text not null default '',
                    supersedes_json text not null default '[]',
                    updated_at text not null
                )
                """);
            st.execute("""
                create table if not exists package_files (
                    realm text not null default 'boilerplate',
                    candidate_id text not null,
                    file_path text not null,
                    size_bytes integer not null default 0,
                    updated_at text not null,
                    primary key(candidate_id, file_path)
                )
                """);
        }
        ensureColumnExists(conn, "package_candidates", "realm", "text not null default 'boilerplate'");
        ensureColumnExists(conn, "package_candidates", "promotion_status", "text not null default 'pending_review'");
        ensureColumnExists(conn, "package_candidates", "promoted_at", "text not null default ''");
        ensureColumnExists(conn, "package_candidates", "promotion_notes", "text not null default ''");
        ensureColumnExists(conn, "package_files", "realm", "text not null default 'boilerplate'");
        try (Statement st = conn.createStatement()) {
            st.execute("create index if not exists idx_package_candidates_realm on package_candidates(realm)");
            st.execute("create index if not exists idx_package_candidates_status on package_candidates(promotion_status)");
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

    private static void syncPolicyRules(Connection conn, Path policySqlPath) throws Exception {
        if (policySqlPath == null || !Files.exists(policySqlPath)) {
            return;
        }
        String sql = Files.readString(policySqlPath, StandardCharsets.UTF_8);
        List<String> statements = splitSqlStatements(sql);
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String statement : statements) {
                String trimmed = statement == null ? "" : statement.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                st.execute(trimmed);
            }
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }
            if (c == ';' && !inSingle && !inDouble) {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            statements.add(current.toString());
        }
        return statements;
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
            "insert into package_candidates(candidate_id,realm,package_id,target_repo,source_repo,created_at,summary,package_zip,patch_count,manifest_file_count,promotion_status,promoted_at,promotion_notes,supersedes_json,updated_at) " +
                "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )) {
            for (CandidateRow row : candidates) {
                ps.setString(1, row.candidateId());
                ps.setString(2, row.realm());
                ps.setString(3, row.packageId());
                ps.setString(4, row.targetRepo());
                ps.setString(5, row.sourceRepo());
                ps.setString(6, row.createdAt());
                ps.setString(7, row.summary());
                ps.setString(8, row.packageZip());
                ps.setInt(9, row.patchCount());
                ps.setInt(10, row.manifestFileCount());
                ps.setString(11, row.promotionStatus());
                ps.setString(12, row.promotedAt());
                ps.setString(13, row.promotionNotes());
                ps.setString(14, GSON.toJson(row.supersedes()));
                ps.setString(15, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = conn.prepareStatement(
            "insert into package_files(realm,candidate_id,file_path,size_bytes,updated_at) values(?,?,?,?,?)"
        )) {
            for (CandidateRow row : candidates) {
                for (PackageFileRow file : row.files()) {
                    ps.setString(1, row.realm());
                    ps.setString(2, row.candidateId());
                    ps.setString(3, file.path());
                    ps.setLong(4, file.sizeBytes());
                    ps.setString(5, now);
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
            String realm = "boilerplate";
            String created = ISO.format(Files.getLastModifiedTime(dir).toInstant().atOffset(ZoneOffset.UTC));
            Path readme = dir.resolve("README.md");
            Path manifest = dir.resolve("package-manifest.json");
            String summary = readSummary(readme);
            String packageId = readManifestString(manifest, "packageId");
            String sourceRepo = readManifestString(manifest, "sourceRepo");
            String targetFromManifest = readManifestString(manifest, "targetRepo");
            List<String> supersedes = readManifestArray(manifest, "supersedes");
            String promotionStatus = normalizePromotionStatus(readManifestString(manifest, "promotionStatus"));
            String promotedAt = readManifestString(manifest, "promotedAt");
            String promotionNotes = readManifestString(manifest, "promotionNotes");
            int patchCount = countFiles(dir.resolve("patches"), ".patch");
            int manifestFileCount = countManifestFiles(manifest);
            List<PackageFileRow> files = listFiles(dir);
            Path zipPath = packagesDir.resolve(candidateId + ".zip");
            String zipRel = Files.exists(zipPath) ? packagesDir.relativize(zipPath).toString().replace('\\', '/') : "";
            if ("pending_review".equals(promotionStatus)) {
                if (!zipRel.isBlank()) {
                    promotionStatus = "approved";
                }
            }
            out.add(new CandidateRow(
                candidateId,
                realm,
                packageId.isBlank() ? candidateId : packageId,
                targetFromManifest.isBlank() ? targetRepo : targetFromManifest,
                sourceRepo,
                created,
                summary,
                zipRel,
                patchCount,
                manifestFileCount,
                promotionStatus,
                promotedAt,
                promotionNotes,
                supersedes,
                files
            ));
        }
        Set<String> supersededByOtherCandidates = new LinkedHashSet<>();
        for (CandidateRow row : out) {
            supersededByOtherCandidates.addAll(row.supersedes());
        }
        if (supersededByOtherCandidates.isEmpty()) {
            return out;
        }
        List<CandidateRow> adjusted = new ArrayList<>(out.size());
        for (CandidateRow row : out) {
            String status = row.promotionStatus();
            if (supersededByOtherCandidates.contains(row.candidateId()) && !"promoted".equals(status)) {
                status = "superseded";
            }
            adjusted.add(new CandidateRow(
                row.candidateId(),
                row.realm(),
                row.packageId(),
                row.targetRepo(),
                row.sourceRepo(),
                row.createdAt(),
                row.summary(),
                row.packageZip(),
                row.patchCount(),
                row.manifestFileCount(),
                status,
                row.promotedAt(),
                row.promotionNotes(),
                row.supersedes(),
                row.files()
            ));
        }
        return adjusted;
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

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
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

    private static void writeCsv(Path path, String[] header, List<String[]> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(joinCsvRow(header));
        for (String[] row : rows) {
            lines.add(joinCsvRow(row));
        }
        Files.write(path, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String joinCsvRow(String[] values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String cell = value == null ? "" : value;
            boolean quote = cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r");
            if (cell.contains("\"")) {
                cell = cell.replace("\"", "\"\"");
            }
            escaped.add(quote ? "\"" + cell + "\"" : cell);
        }
        return String.join(",", escaped);
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void ensureColumnExists(Connection conn, String table, String column, String columnDef) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("alter table " + table + " add column " + column + " " + columnDef);
        } catch (SQLException ignored) {
            // SQLite does not support IF NOT EXISTS for ADD COLUMN; ignore duplicate-column failures.
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

    private static String safeSha256Text(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
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

    private static Path safeMaterializePath(Path targetRoot, String endpoint) {
        Path root = targetRoot.toAbsolutePath().normalize();
        Path rel = Path.of(endpoint).normalize();
        if (rel.isAbsolute()) {
            throw new IllegalArgumentException("Endpoint path must be relative: " + endpoint);
        }
        Path out = root.resolve(rel).normalize();
        if (!out.startsWith(root)) {
            throw new IllegalArgumentException("Endpoint escapes target root: " + endpoint);
        }
        return out;
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

    private static List<String> parseJsonStringArray(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        JsonElement parsed = GSON.fromJson(value, JsonElement.class);
        if (parsed == null || !parsed.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement el : parsed.getAsJsonArray()) {
            if (el != null && el.isJsonPrimitive()) {
                out.add(el.getAsString().trim());
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

    private static String normalizePromotionStatus(String status) {
        String normalized = text(status).toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.isBlank()) {
            return "pending_review";
        }
        return switch (normalized) {
            case "pending", "pending_review", "review" -> "pending_review";
            case "approved", "ready", "ready_for_promotion" -> "approved";
            case "promoted", "merged" -> "promoted";
            case "superseded" -> "superseded";
            default -> "pending_review";
        };
    }

    private static String recommendedActionForStatus(String status) {
        return switch (normalizePromotionStatus(status)) {
            case "approved" -> "Promote package in target boilerplate repository.";
            case "promoted" -> "No action required; keep for audit trail.";
            case "superseded" -> "Archive or remove after confirming successor package is merged.";
            default -> "Review package content and decide approve/supersede.";
        };
    }

    private record CandidateRow(String candidateId,
                                String realm,
                                String packageId,
                                String targetRepo,
                                String sourceRepo,
                                String createdAt,
                                String summary,
                                String packageZip,
                                int patchCount,
                                int manifestFileCount,
                                String promotionStatus,
                                String promotedAt,
                                String promotionNotes,
                                List<String> supersedes,
                                List<PackageFileRow> files) {}

    private record DictionaryRef(String objectName, String sourceRef) {}

    private record PackageFileRow(String path, long sizeBytes) {}
}
