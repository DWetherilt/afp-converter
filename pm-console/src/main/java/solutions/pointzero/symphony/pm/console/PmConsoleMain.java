package solutions.pointzero.symphony.pm.console;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(
    name = "pmconsole",
    mixinStandardHelpOptions = true,
    description = "Development PM console for project state/report tooling.",
    subcommands = {
        PmConsoleMain.StatusCommand.class,
        PmConsoleMain.RefreshCommand.class,
        PmConsoleMain.ToolsCommand.class,
        PmConsoleMain.DecisionsCommand.class,
        PmConsoleMain.LiveCommand.class
    }
)
public final class PmConsoleMain implements Callable<Integer> {

    private static final Path PROJECT_DB = Path.of("pm/state/project-state.sqlite");
    private static final Path BOILERPLATE_DB = Path.of("pm/state/boilerplate-state.sqlite");
    private static final Path DECISION_QUEUE = Path.of("pm/reports/decision-priority-queue.json");
    private static final Path AI_INBOX = Path.of("pm/state/assistant-inbox.ndjson");

    public static void main(String[] args) {
        int exit = new CommandLine(new PmConsoleMain()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        System.out.println("pmconsole: use subcommands status | refresh | tools | decisions | live");
        return 0;
    }

    @Command(name = "tools", description = "Lists PM tooling entry points and paths.")
    static final class ToolsCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            List<String> lines = List.of(
                "PM realm:",
                "  - State DBs: pm/state/project-state.sqlite, pm/state/boilerplate-state.sqlite",
                "  - Reports: pm/reports/*",
                "  - Checkpoints: pm/checkpoints/*",
                "",
                "Primary Gradle PM tasks:",
                "  - projectStateDb, boilerplateStateDb",
                "  - governanceAlerts, versionControlLedger",
                "  - issuesLogTickle, issuesEffectivenessReport",
                "  - projectPlanProgressJson, projectPlanWorkbook",
                "  - policyGovernanceWorkbook, stateInventoryCsv",
                "  - policyRulesReport",
                "  - policyRulesLint, dataDictionaryLint",
                "  - realmCodeDatabases, realmTokenSearch",
                "  - documentationManifest",
                "",
                "Dev helper tasks:",
                "  - pmConsoleStatus",
                "  - pmDevAttach",
                "  - pmWorkflowList",
                "  - pmWorkflowRun (-PpmPhase=<phaseId>)",
                "",
                "Interactive mode:",
                "  - pmconsole live --interval 15"
            );
            lines.forEach(System.out::println);
            return 0;
        }
    }

    @Command(name = "decisions", description = "Shows top ranked entries from pm/reports/decision-priority-queue.json")
    static final class DecisionsCommand implements Callable<Integer> {

        @Option(names = "--top", description = "How many rows to print (default: ${DEFAULT-VALUE})")
        int top = 10;

        @Override
        public Integer call() {
            return printDecisionQueue(Math.max(1, top));
        }
    }

    @Command(name = "live", description = "Top-style live PM dashboard with interactive commands.")
    static final class LiveCommand implements Callable<Integer> {

        @Option(names = "--interval", description = "Refresh interval in seconds (default: ${DEFAULT-VALUE})")
        int intervalSeconds = 15;

        @Option(names = "--top", description = "Number of decisions shown in live view (default: ${DEFAULT-VALUE})")
        int topDecisions = 5;

        @Override
        public Integer call() throws Exception {
            long intervalMs = Math.max(1, intervalSeconds) * 1000L;
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            printLiveHelp();
            long nextRefreshAt = 0L;
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= nextRefreshAt) {
                    clearScreen();
                    System.out.println("PM Console Live");
                    System.out.println("===============");
                    System.out.println("refresh interval: " + (intervalMs / 1000L) + "s");
                    System.out.println();
                    new StatusCommand().call();
                    System.out.println();
                    printDecisionQueue(Math.max(1, topDecisions));
                    System.out.println();
                    System.out.println("Commands: help | status | decisions [n] | refresh | refresh-preview | tools | interval <sec> | prompt <text> | clear | quit");
                    nextRefreshAt = now + intervalMs;
                }

                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        return 0;
                    }
                    String normalized = line.trim();
                    if (normalized.isEmpty()) {
                        nextRefreshAt = 0L;
                        continue;
                    }
                    String lower = normalized.toLowerCase(Locale.ROOT);
                    if (lower.equals("q") || lower.equals("quit") || lower.equals("exit")) {
                        System.out.println("Exiting live console.");
                        return 0;
                    }
                    if (lower.equals("h") || lower.equals("help")) {
                        printLiveHelp();
                        nextRefreshAt = 0L;
                        continue;
                    }
                    if (lower.equals("status") || lower.equals("s")) {
                        nextRefreshAt = 0L;
                        continue;
                    }
                    if (lower.startsWith("decisions")) {
                        Integer n = parseTrailingInt(normalized, "decisions");
                        if (n != null) {
                            topDecisions = Math.max(1, n);
                        }
                        nextRefreshAt = 0L;
                        continue;
                    }
                    if (lower.equals("refresh") || lower.equals("r")) {
                        int exit = runWorkflowPhase("pm_refresh_and_reports");
                        System.out.println("refresh exit code: " + exit);
                        nextRefreshAt = 0L;
                        continue;
                    }
                    if (lower.equals("refresh-preview") || lower.equals("rp")) {
                        int pre = runWorkflowPhase("execute_application_with_pm_console");
                        int post = pre == 0 ? runWorkflowPhase("pm_refresh_and_reports") : pre;
                        System.out.println("refresh-preview exit code: " + post);
                        nextRefreshAt = 0L;
                        continue;
                    }
                    if (lower.equals("tools")) {
                        new ToolsCommand().call();
                        nextRefreshAt = 0L;
                        continue;
                    }
                    if (lower.startsWith("interval ")) {
                        Integer parsed = parseTrailingInt(normalized, "interval");
                        if (parsed != null && parsed > 0) {
                            intervalMs = parsed * 1000L;
                            System.out.println("interval updated to " + parsed + "s");
                        } else {
                            System.out.println("invalid interval; use interval <seconds>");
                        }
                        nextRefreshAt = 0L;
                        continue;
                    }
                    if (lower.startsWith("prompt ") || lower.startsWith("ask ")) {
                        String text = normalized.substring(normalized.indexOf(' ') + 1).trim();
                        if (text.isEmpty()) {
                            System.out.println("prompt text is empty.");
                        } else {
                            appendPrompt(text);
                            System.out.println("prompt captured in " + AI_INBOX);
                        }
                        nextRefreshAt = 0L;
                        continue;
                    }
                    if (lower.equals("clear")) {
                        clearScreen();
                        nextRefreshAt = 0L;
                        continue;
                    }
                    System.out.println("unknown command: " + normalized + " (use 'help')");
                    nextRefreshAt = 0L;
                }
                Thread.sleep(200L);
            }
        }
    }

    @Command(name = "refresh", description = "Runs PM pipeline tasks. Optionally includes preview generation.")
    static final class RefreshCommand implements Callable<Integer> {

        @Option(names = "--with-preview", description = "Also run previewManifest before PM refresh tasks.")
        boolean withPreview;

        @Override
        public Integer call() throws Exception {
            int exit = 0;
            if (withPreview) {
                exit = runWorkflowPhase("execute_application_with_pm_console");
                if (exit != 0) {
                    System.err.println("PM refresh pre-phase failed with exit code " + exit);
                    return exit;
                }
            }
            exit = runWorkflowPhase("pm_refresh_and_reports");
            if (exit != 0) {
                System.err.println("PM refresh failed with exit code " + exit);
            }
            return exit;
        }

        private static int runWorkflowPhase(String phase) throws Exception {
            return PmConsoleMain.runWorkflowPhase(phase);
        }
    }

    @Command(name = "status", description = "Shows an aggregated PM status dashboard from SQLite + reports.")
    static final class StatusCommand implements Callable<Integer> {

        private static final DecimalFormat PCT = new DecimalFormat("0.00");

        @Override
        public Integer call() {
            System.out.println("PM Console Status");
            System.out.println("--------------");
            printFilePresence();
            printProjectSummary();
            printBoilerplateSummary();
            printReportSummary();
            return 0;
        }

        private void printFilePresence() {
            System.out.println("State:");
            System.out.println("  - project DB: " + describePath(PROJECT_DB));
            System.out.println("  - boilerplate DB: " + describePath(BOILERPLATE_DB));
            System.out.println("  - renderer preview PDF: " + describePath(Path.of("preview/afp-output.pdf")));
            System.out.println();
        }

        private void printProjectSummary() {
            if (!Files.exists(PROJECT_DB)) {
                System.out.println("Project DB summary: missing");
                System.out.println();
                return;
            }
            try (Connection conn = connect(PROJECT_DB)) {
                String head = queryString(conn, "select git_head from repo_vcs_snapshot where id = 1", "");
                String branch = queryString(conn, "select git_branch from repo_vcs_snapshot where id = 1", "");
                int dirty = queryInt(conn, "select is_dirty from repo_vcs_snapshot where id = 1", 0);

                int issueCount = queryInt(conn, "select count(*) from issues", 0);
                int openIssues = queryInt(conn,
                    "select count(*) from issues where lower(status) in ('open','in progress','active')", 0);
                int highOpen = queryInt(conn,
                    "select count(*) from issues where lower(status) in ('open','in progress','active') and lower(severity)='high'", 0);

                int taskCount = queryInt(conn, "select count(*) from plan_tasks", 0);
                double avgCompletion = queryDouble(conn,
                    "select avg(cast(replace(percent_complete, '%', '') as real)) from plan_tasks", 0.0d);

                int activeGovernance = queryInt(conn,
                    "select count(*) from governance_events where lower(status) in ('open','in_progress','breach')", 0);
                int policyRules = queryInt(conn, "select count(*) from policy_rule_catalog where enabled = 1", 0);
                int nextTenRuleEnabled = queryInt(conn,
                    "select count(*) from policy_rule_catalog where rule_id = 'PM-COMMS-001' and enabled = 1", 0);

                System.out.println("Project DB summary:");
                System.out.println("  - git: " + branch + " @ " + shorten(head) + (dirty == 1 ? " (dirty)" : " (clean)"));
                System.out.println("  - issues: total=" + issueCount + ", open=" + openIssues + ", high-open=" + highOpen);
                System.out.println("  - plan tasks: " + taskCount + ", avg completion=" + PCT.format(avgCompletion) + "%");
                System.out.println("  - active governance events: " + activeGovernance);
                System.out.println("  - enabled policy rules (SQL): " + policyRules);
                System.out.println("  - next-10 response policy (PM-COMMS-001): " + (nextTenRuleEnabled > 0 ? "active" : "missing/disabled"));
                System.out.println();
            } catch (Exception e) {
                System.out.println("Project DB summary: error -> " + e.getMessage());
                System.out.println();
            }
        }

        private void printBoilerplateSummary() {
            if (!Files.exists(BOILERPLATE_DB)) {
                System.out.println("Boilerplate DB summary: missing");
                System.out.println();
                return;
            }
            try (Connection conn = connect(BOILERPLATE_DB)) {
                int candidates = queryInt(conn, "select count(*) from package_candidates", 0);
                int files = queryInt(conn, "select count(*) from package_files", 0);
                System.out.println("Boilerplate DB summary:");
                System.out.println("  - package candidates: " + candidates);
                System.out.println("  - package files indexed: " + files);
                System.out.println();
            } catch (Exception e) {
                System.out.println("Boilerplate DB summary: error -> " + e.getMessage());
                System.out.println();
            }
        }

        private void printReportSummary() {
            Path report = Path.of("pm/reports/issues-effectiveness.json");
            Path alerts = Path.of("pm/reports/governance-alerts.json");
            Path policyRules = Path.of("pm/reports/policy-rules.json");
            Path policyRulesLint = Path.of("pm/reports/policy-rules-lint.json");
            System.out.println("Report summary:");
            System.out.println("  - issues effectiveness: " + describePath(report));
            System.out.println("  - governance alerts: " + describePath(alerts));
            System.out.println("  - policy rules: " + describePath(policyRules));
            System.out.println("  - policy rules lint: " + describePath(policyRulesLint));

            JsonObject issues = readJson(report);
            if (issues != null) {
                JsonObject summary = obj(issues, "summary");
                if (summary != null) {
                    System.out.println("  - effectiveness signals: requiresIssuesLogUpdate=" + str(summary, "requiresIssuesLogUpdate", "false")
                        + ", open issues=" + str(summary, "openIssueCount", "0"));
                }
            }

            JsonObject governance = readJson(alerts);
            if (governance != null) {
                System.out.println("  - active governance breach: " + str(governance, "hasActiveGovernanceBreach", "false"));
            }

            JsonObject lint = readJson(policyRulesLint);
            if (lint != null) {
                System.out.println("  - policy lint ok: " + str(lint, "ok", "false")
                    + ", missingRuleIds=" + str(lint, "missingRuleIds", "[]"));
            }
        }

        private static Connection connect(Path dbPath) throws SQLException {
            return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        }

        private static int queryInt(Connection conn, String sql, int fallback) {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (Exception ignored) {
                return fallback;
            }
            return fallback;
        }

        private static double queryDouble(Connection conn, String sql, double fallback) {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            } catch (Exception ignored) {
                return fallback;
            }
            return fallback;
        }

        private static String queryString(Connection conn, String sql, String fallback) {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString(1);
                    return val == null ? fallback : val;
                }
            } catch (Exception ignored) {
                return fallback;
            }
            return fallback;
        }

        private static String describePath(Path path) {
            try {
                if (!Files.exists(path)) {
                    return "missing (" + path + ")";
                }
                long size = Files.size(path);
                return "present (" + path + ", " + size + " bytes)";
            } catch (IOException e) {
                return "present (" + path + ")";
            }
        }

        private static String shorten(String head) {
            if (head == null || head.isBlank()) {
                return "<unknown>";
            }
            return head.length() <= 12 ? head : head.substring(0, 12);
        }

        private static JsonObject readJson(Path path) {
            if (!Files.exists(path)) {
                return null;
            }
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed != null && parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        private static JsonObject obj(JsonObject root, String key) {
            if (root == null || !root.has(key) || !root.get(key).isJsonObject()) {
                return null;
            }
            return root.getAsJsonObject(key);
        }

        private static String str(JsonObject root, String key, String fallback) {
            if (root == null || !root.has(key) || root.get(key).isJsonNull()) {
                return fallback;
            }
            try {
                return root.get(key).getAsString();
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }

    private static int runWorkflowPhase(String phase) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("python3");
        cmd.add("tools/pm_workflow.py");
        cmd.add("run");
        cmd.add("--adapter");
        cmd.add("gradle");
        cmd.add("--phase");
        cmd.add(phase);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Path.of(".").toFile());
        pb.inheritIO();
        Process p = pb.start();
        return p.waitFor();
    }

    private static int printDecisionQueue(int top) {
        System.out.println("Decision queue:");
        JsonObject queue = readJson(DECISION_QUEUE);
        if (queue == null) {
            System.out.println("  - missing (" + DECISION_QUEUE + ")");
            return 1;
        }
        String count = str(queue, "candidateCount", "0");
        System.out.println("  - candidates: " + count);
        JsonElement rows = queue.get("decisions");
        if (rows == null || !rows.isJsonArray()) {
            return 0;
        }
        int printed = 0;
        for (JsonElement el : rows.getAsJsonArray()) {
            if (printed >= top) {
                break;
            }
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject row = el.getAsJsonObject();
            System.out.println("  - [" + str(row, "priorityBucket", "P3") + "] "
                + str(row, "decisionId", "<id>") + " | "
                + str(row, "realm", "<realm>") + " | "
                + str(row, "status", "<status>") + " | impact="
                + str(row, "impactScore", "0")
                + " | " + str(row, "title", ""));
            printed++;
        }
        return 0;
    }

    private static void appendPrompt(String text) throws IOException {
        Path parent = AI_INBOX.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("capturedAt", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString());
        payload.addProperty("source", "pmconsole-live");
        payload.addProperty("prompt", text);
        Files.writeString(
            AI_INBOX,
            payload.toString() + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private static void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private static Integer parseTrailingInt(String raw, String prefix) {
        String value = raw.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void printLiveHelp() {
        System.out.println("pmconsole live commands:");
        System.out.println("  - help");
        System.out.println("  - status");
        System.out.println("  - decisions [n]");
        System.out.println("  - refresh");
        System.out.println("  - refresh-preview");
        System.out.println("  - tools");
        System.out.println("  - interval <seconds>");
        System.out.println("  - prompt <text>  (alias: ask <text>)");
        System.out.println("  - clear");
        System.out.println("  - quit");
        System.out.println();
    }

    private static JsonObject readJson(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject root, String key, String fallback) {
        if (root == null || !root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return root.get(key).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
