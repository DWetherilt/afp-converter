package solutions.pointzero.symphony.pm.console;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "pmconsole",
    mixinStandardHelpOptions = true,
    description = "Development PM console for project state/report tooling.",
    subcommands = {
        PmConsoleMain.StatusCommand.class,
        PmConsoleMain.RefreshCommand.class,
        PmConsoleMain.ToolsCommand.class
    }
)
public final class PmConsoleMain implements Callable<Integer> {

    private static final Path PROJECT_DB = Path.of("pm/state/project-state.sqlite");
    private static final Path BOILERPLATE_DB = Path.of("pm/state/boilerplate-state.sqlite");

    public static void main(String[] args) {
        int exit = new CommandLine(new PmConsoleMain()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        System.out.println("pmconsole: use subcommands status | refresh | tools");
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
                "  - pmWorkflowRun (-PpmPhase=<phaseId>)"
            );
            lines.forEach(System.out::println);
            return 0;
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
}
