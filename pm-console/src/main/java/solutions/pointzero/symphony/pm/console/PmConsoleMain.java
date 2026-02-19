package solutions.pointzero.symphony.pm.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
    name = "pmconsole",
    mixinStandardHelpOptions = true,
    description = "Development PM console for project state/report tooling.",
    subcommands = {
        PmConsoleMain.StatusCommand.class,
        PmConsoleMain.DecisionsCommand.class,
        PmConsoleMain.ActionsCommand.class,
        PmConsoleMain.ApplySuggestionCommand.class,
        PmConsoleMain.ReportCommand.class,
        PmConsoleMain.RefreshCommand.class,
        PmConsoleMain.DbCommand.class,
        PmConsoleMain.ToolsCommand.class,
        PmConsoleMain.LiveCommand.class
    }
)
public final class PmConsoleMain implements Callable<Integer> {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Path PROJECT_DB = Path.of("pm/state/project-state.sqlite");
    private static final Path BOILERPLATE_DB = Path.of("pm/state/boilerplate-state.sqlite");
    private static final Path DECISION_QUEUE = Path.of("pm/reports/decision-priority-queue.json");
    private static final Path ACTION_ITEMS = Path.of("pm/reports/action-items.json");
    private static final Path ACTION_OWNER_SUMMARY = Path.of("pm/reports/action-owner-summary.json");
    private static final Path ACTION_SLA_TREND = Path.of("pm/reports/action-sla-trend.json");
    private static final Path ACTION_INGEST_STRICT = Path.of("pm/reports/action-ingest-strict.json");
    private static final Path ACTION_SUGGESTIONS = Path.of("pm/reports/action-decision-suggestions.json");
    private static final Path REALM_KNOWLEDGE_SYNC = Path.of("pm/reports/realm-knowledge-sync.json");
    private static final Path REALM_KNOWLEDGE_LINK_GATE = Path.of("pm/reports/realm-knowledge-link-gate.json");
    private static final Path AI_INBOX = Path.of("pm/state/assistant-inbox.ndjson");
    private static final Path AUTHORIZED_DBS = Path.of("pm/security/authorized-databases.json");

    private static final String HUMAN_APPROVAL_TOKEN = "I_HAVE_EXPLICIT_HUMAN_APPROVAL";

    public static void main(String[] args) {
        int exit = new CommandLine(new PmConsoleMain()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        System.out.println("pmconsole: use subcommands status | decisions | actions | apply-suggestion | report | refresh | db | tools | live");
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
                "  - Authorized DB catalog: pm/security/authorized-databases.json",
                "",
                "Primary console commands:",
                "  - pmconsole status",
                "  - pmconsole decisions --top 10",
                "  - pmconsole actions --top 10 --owner ai-agent --open-only",
                "  - pmconsole apply-suggestion --action-id action::xxxx",
                "  - pmconsole report --request \"current workstream status\"",
                "  - pmconsole report --request \"reasoning drive\"",
                "  - pmconsole report --request \"governance status\"",
                "  - pmconsole refresh --phase pm_refresh_and_reports",
                "  - pmconsole refresh --tasks projectStateDb,decisionPriorityReport",
                "  - pmconsole db --list",
                "",
                "Interactive mode:",
                "  - pmconsole live --interval 15"
            );
            lines.forEach(System.out::println);
            return 0;
        }
    }

    @Command(name = "db", description = "Lists or updates authorized database aliases for report federation.")
    static final class DbCommand implements Callable<Integer> {

        @Option(names = "--list", description = "List authorized database aliases.")
        boolean list;

        @Option(names = "--authorize", description = "Add/update an authorized database alias.")
        boolean authorize;

        @Option(names = "--alias", description = "Alias for database authorization.")
        String alias;

        @Option(names = "--path", description = "Database file path.")
        String path;

        @Option(names = "--description", description = "Description for this alias.")
        String description = "";

        @Option(names = "--enabled", description = "Enable/disable alias (default: ${DEFAULT-VALUE}).")
        boolean enabled = true;

        @Option(names = "--human-approved", description = "Explicit human approval token required for mutations.")
        String humanApproved;

        @Override
        public Integer call() throws Exception {
            ensureAuthorizedDatabasesFile();
            List<AuthorizedDb> dbs = loadAuthorizedDatabases();
            if (!authorize || list) {
                System.out.println("Authorized databases:");
                for (AuthorizedDb db : dbs) {
                    System.out.println("  - " + db.alias + " -> " + db.path + " (enabled=" + db.enabled + ", readOnly=" + db.readOnly + ") " + db.description);
                }
            }
            if (!authorize) {
                return 0;
            }
            if (!HUMAN_APPROVAL_TOKEN.equals(text(humanApproved))) {
                throw new IllegalArgumentException("Missing/invalid --human-approved token for db authorization update.");
            }
            if (text(alias).isBlank() || text(path).isBlank()) {
                throw new IllegalArgumentException("--alias and --path are required with --authorize.");
            }
            Map<String, AuthorizedDb> byAlias = new LinkedHashMap<>();
            for (AuthorizedDb db : dbs) {
                byAlias.put(db.alias, db);
            }
            byAlias.put(text(alias), new AuthorizedDb(text(alias), text(path), enabled, true, text(description)));
            saveAuthorizedDatabases(new ArrayList<>(byAlias.values()));
            System.out.println("authorized_db_updated:" + alias);
            return 0;
        }
    }

    @Command(name = "decisions", description = "Shows top ranked entries from pm/reports/decision-priority-queue.json")
    static final class DecisionsCommand implements Callable<Integer> {

        @Option(names = "--top", description = "How many rows to print (default: ${DEFAULT-VALUE})")
        int top = 10;

        @Option(names = "--id-mode", description = "Identifier display mode: friendly|raw (default: ${DEFAULT-VALUE})")
        String idMode = "friendly";

        @Override
        public Integer call() {
            return printDecisionQueue(Math.max(1, top), idMode);
        }
    }

    @Command(name = "actions", description = "Shows actionable items from pm/reports/action-items.json")
    static final class ActionsCommand implements Callable<Integer> {

        @Option(names = "--top", description = "How many rows to print (default: ${DEFAULT-VALUE})")
        int top = 10;

        @Option(names = "--priority", description = "Optional priority filter: high|medium|low")
        String priority;

        @Option(names = "--stale-only", description = "Show only stale action rows.")
        boolean staleOnly;

        @Option(names = "--owner", description = "Optional owner filter (exact match).")
        String owner;

        @Option(names = "--open-only", description = "Show only open/in_progress/blocked action rows.")
        boolean openOnly;

        @Option(names = "--id-mode", description = "Identifier display mode: friendly|raw (default: ${DEFAULT-VALUE})")
        String idMode = "friendly";

        @Override
        public Integer call() {
            return printActionItems(Math.max(1, top), priority, staleOnly, owner, openOnly, idMode);
        }
    }

    @Command(name = "apply-suggestion", description = "Applies the top action->decision suggestion from action suggestion report.")
    static final class ApplySuggestionCommand implements Callable<Integer> {

        @Option(names = "--action-id", description = "Optional action ID; defaults to first actionable suggestion.")
        String actionId;

        @Option(names = "--dry-run", description = "Preview selected suggestion without writing changes.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            return applyTopSuggestion(actionId, dryRun);
        }
    }

    @Command(name = "report", description = "Generates a screen payload from an intent-like request (text or JSON output).")
    static final class ReportCommand implements Callable<Integer> {

        @Option(names = "--request", required = true, description = "Request string, e.g. 'current workstream status'.")
        String request;

        @Option(names = "--dbs", description = "Comma-separated DB aliases from authorized catalog.")
        String dbs;

        @Option(names = "--format", description = "Output format: text|json (default: ${DEFAULT-VALUE})")
        String format = "text";

        @Option(names = "--output", description = "Optional output file for JSON payload.")
        String output;

        @Override
        public Integer call() throws Exception {
            ReportPayload payload = generateReportPayload(text(request), resolveAliases(dbs));
            if ("json".equalsIgnoreCase(text(format))) {
                String rendered = GSON.toJson(payload.payload);
                if (!text(output).isBlank()) {
                    Path out = Path.of(text(output));
                    ensureParent(out);
                    Files.writeString(out, rendered + "\n", StandardCharsets.UTF_8);
                    System.out.println("report_written:" + out);
                } else {
                    System.out.println(rendered);
                }
                return 0;
            }
            printReportText(payload);
            return 0;
        }
    }

    @Command(name = "refresh", description = "Runs PM refresh using granular phase/task controls.")
    static final class RefreshCommand implements Callable<Integer> {

        @Option(names = "--with-preview", description = "Run execute_application_with_pm_console before refresh.")
        boolean withPreview;

        @Option(names = "--phase", description = "Workflow phase id (e.g. pm_refresh_and_reports).")
        String phase;

        @Option(names = "--tasks", description = "Comma-separated Gradle tasks to run directly.")
        String tasks;

        @Option(names = "--dry-run", description = "Print command(s) without executing.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            if (!text(tasks).isBlank()) {
                List<String> taskList = splitCsv(tasks);
                if (taskList.isEmpty()) {
                    throw new IllegalArgumentException("--tasks provided but no tasks parsed");
                }
                return runGradleTasks(taskList, dryRun);
            }

            String selectedPhase = text(phase);
            if (selectedPhase.isBlank()) {
                selectedPhase = "pm_refresh_and_reports";
            }
            if (withPreview) {
                int pre = runWorkflowPhase("execute_application_with_pm_console", dryRun);
                if (pre != 0) {
                    return pre;
                }
            }
            return runWorkflowPhase(selectedPhase, dryRun);
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
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.println("PM Console Interactive");
            System.out.println("======================");
            System.out.println("Type a command and press Enter.");
            System.out.println();
            printLiveHelp();
            while (true) {
                System.out.print("pm> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    return 0;
                }
                String normalized = line.trim();
                if (normalized.isEmpty()) {
                    continue;
                }
                String lower = normalized.toLowerCase(Locale.ROOT);
                if (Set.of("q", "quit", "exit").contains(lower)) {
                    System.out.println("Exiting live console.");
                    return 0;
                }
                if (Set.of("h", "help").contains(lower)) {
                    printLiveHelp();
                    continue;
                }
                if (Set.of("status", "s").contains(lower)) {
                    new StatusCommand().call();
                    System.out.println();
                    continue;
                }
                if (lower.startsWith("decisions")) {
                    Integer n = parseTrailingInt(normalized, "decisions");
                    if (n != null) {
                        topDecisions = Math.max(1, n);
                    }
                    String idMode = lower.contains("raw") ? "raw" : "friendly";
                    printDecisionQueue(Math.max(1, topDecisions), idMode);
                    System.out.println();
                    continue;
                }
                if (lower.startsWith("actions")) {
                    LiveActionSelection parsed = parseLiveActionsSelection(normalized);
                    printActionItems(parsed.top, parsed.priority, parsed.staleOnly, parsed.owner, parsed.openOnly, parsed.idMode);
                    System.out.println();
                    continue;
                }
                if (lower.startsWith("apply-suggestion")) {
                    String[] parts = normalized.split("\\s+");
                    String actionId = parts.length >= 2 ? parts[1].trim() : "";
                    boolean dryRun = lower.contains("dry-run");
                    int exit = applyTopSuggestion(actionId, dryRun);
                    System.out.println("apply-suggestion exit code: " + exit);
                    System.out.println();
                    continue;
                }
                if (lower.startsWith("report ")) {
                    String req = normalized.substring("report ".length()).trim();
                    if (req.isEmpty()) {
                        System.out.println("report request is empty");
                    } else {
                        printReportText(generateReportPayload(req, resolveAliases("")));
                    }
                    System.out.println();
                    continue;
                }
                if (lower.equals("refresh") || lower.equals("r")) {
                    int exit = runWorkflowPhase("pm_refresh_and_reports", false);
                    System.out.println("refresh exit code: " + exit);
                    System.out.println();
                    continue;
                }
                if (lower.startsWith("refresh phase ")) {
                    String phase = normalized.substring("refresh phase ".length()).trim();
                    int exit = runWorkflowPhase(text(phase).isBlank() ? "pm_refresh_and_reports" : phase, false);
                    System.out.println("refresh phase exit code: " + exit);
                    System.out.println();
                    continue;
                }
                if (lower.startsWith("refresh tasks ")) {
                    String t = normalized.substring("refresh tasks ".length()).trim();
                    int exit = runGradleTasks(splitCsv(t), false);
                    System.out.println("refresh tasks exit code: " + exit);
                    System.out.println();
                    continue;
                }
                if (lower.equals("refresh preview") || lower.equals("refresh-preview") || lower.equals("rp")) {
                    int pre = runWorkflowPhase("execute_application_with_pm_console", false);
                    int post = pre == 0 ? runWorkflowPhase("pm_refresh_and_reports", false) : pre;
                    System.out.println("refresh-preview exit code: " + post);
                    System.out.println();
                    continue;
                }
                if (lower.equals("db list")) {
                    DbCommand db = new DbCommand();
                    db.list = true;
                    db.call();
                    System.out.println();
                    continue;
                }
                if (lower.equals("tools")) {
                    new ToolsCommand().call();
                    System.out.println();
                    continue;
                }
                if (lower.startsWith("interval ")) {
                    Integer parsed = parseTrailingInt(normalized, "interval");
                    if (parsed != null && parsed > 0) {
                        intervalSeconds = parsed;
                        System.out.println("interval set to " + parsed + "s (used only by external watcher wrappers)");
                    } else {
                        System.out.println("invalid interval; use interval <seconds>");
                    }
                    System.out.println();
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
                    System.out.println();
                    continue;
                }
                if (lower.equals("clear")) {
                    clearScreen();
                    continue;
                }
                System.out.println("unknown command: " + normalized + " (use 'help')");
                System.out.println();
            }
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
            System.out.println("  - decision queue: " + describePath(DECISION_QUEUE));
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
            Path issues = Path.of("pm/reports/issues-effectiveness.json");
            Path alerts = Path.of("pm/reports/governance-alerts.json");
            Path policyRules = Path.of("pm/reports/policy-rules.json");
            Path policyLint = Path.of("pm/reports/policy-rules-lint.json");
            Path knowledge = Path.of("pm/reports/knowledge-base.json");
            Path reasoning = Path.of("pm/reports/reasoning-drive.json");
            Path actions = Path.of("pm/reports/action-items.json");

            System.out.println("Report summary:");
            System.out.println("  - issues effectiveness: " + describePath(issues));
            System.out.println("  - governance alerts: " + describePath(alerts));
            System.out.println("  - policy rules: " + describePath(policyRules));
            System.out.println("  - policy rules lint: " + describePath(policyLint));
            System.out.println("  - knowledge base: " + describePath(knowledge));
            System.out.println("  - reasoning drive: " + describePath(reasoning));
            System.out.println("  - action items: " + describePath(actions));
            System.out.println("  - action owner summary: " + describePath(ACTION_OWNER_SUMMARY));
            System.out.println("  - action SLA trend: " + describePath(ACTION_SLA_TREND));
        }
    }

    private static int printDecisionQueue(int top, String idModeRaw) {
        String idMode = normalizeIdMode(idModeRaw);
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
            String displayId = "raw".equals(idMode)
                ? str(row, "decisionId", "<id>")
                : str(row, "decisionRef", str(row, "decisionId", "<id>"));
            System.out.println("  - [" + str(row, "priorityBucket", "P3") + "] "
                + displayId + " | "
                + str(row, "realm", "<realm>") + " | "
                + str(row, "status", "<status>") + " | impact="
                + str(row, "impactScore", "0")
                + " | " + str(row, "title", ""));
            printed++;
        }
        return 0;
    }

    private static int printActionItems(int top, String priorityFilter, boolean staleOnly, String ownerFilter, boolean openOnly, String idModeRaw) {
        String idMode = normalizeIdMode(idModeRaw);
        System.out.println("Action items:");
        JsonObject root = readJson(ACTION_ITEMS);
        if (root == null) {
            System.out.println("  - missing (" + ACTION_ITEMS + ")");
            return 1;
        }
        String count = str(root, "itemCount", "0");
        String normalizedPriority = normalizePriority(priorityFilter);
        String owner = text(ownerFilter);
        System.out.println("  - total: " + count
            + (normalizedPriority.isBlank() ? "" : " | priority=" + normalizedPriority)
            + (staleOnly ? " | staleOnly=true" : "")
            + (owner.isBlank() ? "" : " | owner=" + owner)
            + (openOnly ? " | openOnly=true" : ""));
        JsonElement rows = root.get("actions");
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
            String priority = text(str(row, "priority", "medium")).toLowerCase(Locale.ROOT);
            if (!normalizedPriority.isBlank() && !normalizedPriority.equals(priority)) {
                continue;
            }
            boolean stale = Boolean.parseBoolean(str(row, "isStale", "false"));
            if (staleOnly && !stale) {
                continue;
            }
            String rowOwner = text(str(row, "owner", ""));
            if (!owner.isBlank() && !owner.equals(rowOwner)) {
                continue;
            }
            String status = text(str(row, "status", "")).toLowerCase(Locale.ROOT);
            if (openOnly && !Set.of("open", "in_progress", "blocked").contains(status)) {
                continue;
            }
            String displayId = "raw".equals(idMode)
                ? str(row, "actionId", "<id>")
                : str(row, "actionRef", str(row, "actionId", "<id>"));
            System.out.println("  - [" + str(row, "priority", "medium") + "] "
                + displayId + " | "
                + str(row, "realm", "<realm>") + " | "
                + status + " | "
                + str(row, "title", "")
                + " | stale=" + stale);
            printed++;
        }
        return 0;
    }

    private static void printReportText(ReportPayload report) {
        JsonObject payload = report.payload;
        JsonObject data = payload.getAsJsonObject("data");
        System.out.println("Screen payload:");
        System.out.println("  - screenId: " + str(payload, "screenId", ""));
        System.out.println("  - request: " + str(payload, "request", ""));
        System.out.println("  - generatedAt: " + str(payload, "generatedAt", ""));
        System.out.println("  - sources: " + report.sourcesSummary);

        String screenId = str(payload, "screenId", "");
        if ("workstream_status".equals(screenId)) {
            JsonArray workstreams = data.getAsJsonArray("workstreams");
            JsonArray inProgress = data.getAsJsonArray("topInProgressTasks");
            JsonArray decisions = data.getAsJsonArray("decisionSignals");

            System.out.println();
            System.out.println("Workstreams:");
            if (workstreams != null) {
                for (JsonElement el : workstreams) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - " + str(row, "workstream", "")
                        + " | tasks=" + str(row, "taskCount", "0")
                        + " | complete=" + str(row, "completeCount", "0")
                        + " | inProgress=" + str(row, "inProgressCount", "0")
                        + " | avg=" + str(row, "avgCompletion", "0") + "%");
                }
            }

            System.out.println();
            System.out.println("Top in-progress tasks:");
            if (inProgress != null) {
                for (JsonElement el : inProgress) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - " + str(row, "workstream", "") + " | " + str(row, "task", "")
                        + " | priority=" + str(row, "priority", "")
                        + " | completion=" + str(row, "percentComplete", "0") + "%");
                }
            }

            System.out.println();
            System.out.println("Decision signals:");
            if (decisions != null) {
                for (JsonElement el : decisions) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - alias=" + str(row, "alias", "")
                        + " | realm=" + str(row, "realm", "")
                        + " | total=" + str(row, "decisionCount", "0")
                        + " | inProgress=" + str(row, "inProgressCount", "0")
                        + " | topPriority=" + str(row, "topPriorityBucket", "P3"));
                }
            }
            return;
        }
        if ("reasoning_drive".equals(screenId)) {
            JsonArray heuristics = data.getAsJsonArray("topHeuristics");
            JsonArray insights = data.getAsJsonArray("topInsights");

            System.out.println();
            System.out.println("Top heuristics:");
            if (heuristics != null) {
                for (JsonElement el : heuristics) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - " + str(row, "heuristicKey", "")
                        + " | score=" + str(row, "score", "0")
                        + " | samples=" + str(row, "sampleCount", "0")
                        + " | " + str(row, "signal", ""));
                }
            }

            System.out.println();
            System.out.println("Top derived insights:");
            if (insights != null) {
                for (JsonElement el : insights) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - " + str(row, "realm", "")
                        + " | support=" + str(row, "supportScore", "0")
                        + " | " + str(row, "statement", ""));
                }
            }
            return;
        }
        if ("action_status".equals(screenId)) {
            JsonObject summary = data.getAsJsonObject("summary");
            JsonArray owners = data.getAsJsonArray("owners");
            JsonArray suggestions = data.getAsJsonArray("topSuggestions");
            JsonObject strict = data.getAsJsonObject("ingestStrict");

            System.out.println();
            System.out.println("Action summary:");
            if (summary != null) {
                System.out.println("  - total=" + str(summary, "total", "0")
                    + " | open=" + str(summary, "open", "0")
                    + " | stale=" + str(summary, "stale", "0"));
            }
            if (strict != null) {
                System.out.println("  - ingestStrict=" + str(strict, "status", "UNKNOWN")
                    + " | missing=" + str(strict, "missingCount", "0"));
            }

            System.out.println();
            System.out.println("Owners:");
            if (owners != null) {
                for (JsonElement el : owners) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - " + str(row, "owner", "unassigned")
                        + " | total=" + str(row, "total", "0")
                        + " | open=" + str(row, "open", "0")
                        + " | stale=" + str(row, "stale", "0"));
                }
            }

            System.out.println();
            System.out.println("Top suggestions:");
            if (suggestions != null) {
                for (JsonElement el : suggestions) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - " + str(row, "actionRef", str(row, "actionId", ""))
                        + " -> " + str(row, "decisionRef", str(row, "decisionId", ""))
                        + " | score=" + str(row, "score", "0"));
                }
            }
            return;
        }
        if ("realm_knowledge_status".equals(screenId)) {
            JsonArray realms = data.getAsJsonArray("realms");
            JsonObject gate = data.getAsJsonObject("linkGate");
            System.out.println();
            System.out.println("Realm knowledge status:");
            if (realms != null) {
                for (JsonElement el : realms) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - " + str(row, "realm", "")
                        + " | knowledge=" + str(row, "knowledgeCount", "0")
                        + " | evidence=" + str(row, "evidenceCount", "0")
                        + " | links=" + str(row, "decisionLinkCount", "0")
                        + " | unresolved=" + str(row, "unresolvedDecisionLinks", "0"));
                }
            }
            if (gate != null) {
                System.out.println("  - gate=" + str(gate, "overallStatus", "UNKNOWN")
                    + " | unresolvedTotal=" + str(gate, "unresolvedTotal", "0"));
            }
            return;
        }
        if ("governance_status".equals(screenId)) {
            JsonArray active = data.getAsJsonArray("activeBreaches");
            JsonArray trust = data.getAsJsonArray("trustEvents");
            JsonArray policy = data.getAsJsonArray("policyEvents");
            JsonObject audit = data.getAsJsonObject("policyRuleAudit");

            System.out.println();
            System.out.println("Governance status:");
            System.out.println("  - activeBreaches=" + (active == null ? 0 : active.size())
                + " | trustEvents=" + (trust == null ? 0 : trust.size())
                + " | policyEvents=" + (policy == null ? 0 : policy.size()));
            if (audit != null) {
                System.out.println("  - requiredRule=" + str(audit, "requiredRuleId", "PM-COMMS-001")
                    + " | enabled=" + str(audit, "requiredRuleEnabled", "false"));
            }
            if (active != null) {
                for (JsonElement el : active) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    System.out.println("  - breach: " + str(row, "event_ref", str(row, "event_id", ""))
                        + " | phase=" + str(row, "phase", "")
                        + " | status=" + str(row, "status", ""));
                }
            }
            return;
        }

        System.out.println();
        System.out.println(GSON.toJson(payload));
    }

    private static ReportPayload generateReportPayload(String request, List<String> aliases) throws Exception {
        String normalized = text(request).toLowerCase(Locale.ROOT);
        if (normalized.contains("workstream") && normalized.contains("status")) {
            return buildWorkstreamStatusPayload(request, aliases);
        }
        if (normalized.contains("reasoning") && (normalized.contains("drive") || normalized.contains("status") || normalized.contains("signal"))) {
            return buildReasoningDrivePayload(request, aliases);
        }
        if (normalized.contains("action") && (normalized.contains("status") || normalized.contains("summary"))) {
            return buildActionStatusPayload(request, aliases);
        }
        if (normalized.contains("realm") && normalized.contains("knowledge") && normalized.contains("status")) {
            return buildRealmKnowledgeStatusPayload(request, aliases);
        }
        if (normalized.contains("governance") && (normalized.contains("status") || normalized.contains("alert"))) {
            return buildGovernanceStatusPayload(request, aliases);
        }

        JsonObject data = new JsonObject();
        JsonArray intents = new JsonArray();
        intents.add("current workstream status");
        intents.add("reasoning drive");
        intents.add("action status");
        intents.add("realm knowledge status");
        intents.add("governance status");
        data.add("supportedRequests", intents);
        data.addProperty("message", "Request was not recognized. Try: 'current workstream status', 'reasoning drive', 'action status', 'realm knowledge status', or 'governance status'.");
        JsonObject payload = buildScreenPayload("unsupported_request", request, aliases, data);
        return new ReportPayload(payload, aliases.toString());
    }

    private static ReportPayload buildGovernanceStatusPayload(String request, List<String> aliases) {
        JsonObject data = new JsonObject();
        JsonObject governance = readJson(Path.of("pm/reports/governance-alerts.json"));
        if (governance == null) {
            governance = new JsonObject();
        }
        data.add("activeBreaches", governance.has("activeBreaches") ? governance.get("activeBreaches") : new JsonArray());
        data.add("trustEvents", governance.has("trustEvents") ? governance.get("trustEvents") : new JsonArray());
        data.add("policyEvents", governance.has("policyGovernanceEvents") ? governance.get("policyGovernanceEvents") : new JsonArray());
        data.add("policyRuleAudit", governance.has("policyRuleAudit") ? governance.get("policyRuleAudit") : new JsonObject());
        JsonObject payload = buildScreenPayload("governance_status", request, aliases, data);
        return new ReportPayload(payload, aliases.toString());
    }

    private static ReportPayload buildWorkstreamStatusPayload(String request, List<String> aliases) throws Exception {
        Map<String, AuthorizedDb> auth = authorizedDbMap();
        AuthorizedDb project = requireAlias(auth, aliases, "project");

        JsonArray workstreams = new JsonArray();
        JsonArray topInProgressTasks = new JsonArray();
        JsonArray decisionSignals = new JsonArray();

        try (Connection conn = connect(Path.of(project.path))) {
            try (PreparedStatement ps = conn.prepareStatement(
                "select workstream, count(*) as task_count, " +
                    "sum(case when lower(status)='complete' then 1 else 0 end) as complete_count, " +
                    "sum(case when lower(status) in ('in progress','in_progress') then 1 else 0 end) as in_progress_count, " +
                    "avg(cast(replace(percent_complete, '%', '') as real)) as avg_completion " +
                    "from plan_tasks group by workstream order by workstream asc"
            ); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("workstream", text(rs.getString(1)));
                    row.addProperty("taskCount", rs.getInt(2));
                    row.addProperty("completeCount", rs.getInt(3));
                    row.addProperty("inProgressCount", rs.getInt(4));
                    row.addProperty("avgCompletion", round2(rs.getDouble(5)));
                    workstreams.add(row);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "select workstream, task, priority, cast(replace(percent_complete, '%', '') as real) as pct " +
                    "from plan_tasks where lower(status) in ('in progress','in_progress') " +
                    "order by case lower(priority) when 'high' then 0 when 'medium' then 1 else 2 end, pct desc, workstream asc limit 10"
            ); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("workstream", text(rs.getString(1)));
                    row.addProperty("task", text(rs.getString(2)));
                    row.addProperty("priority", text(rs.getString(3)));
                    row.addProperty("percentComplete", round2(rs.getDouble(4)));
                    topInProgressTasks.add(row);
                }
            }
        }

        Set<String> seen = new LinkedHashSet<>(aliases);
        for (String alias : seen) {
            AuthorizedDb db = auth.get(alias);
            if (db == null || !db.enabled) {
                continue;
            }
            Path path = Path.of(db.path);
            if (!Files.exists(path)) {
                continue;
            }
            try (Connection conn = connect(path)) {
                if (!tableExists(conn, "decision_log")) {
                    continue;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                    "select realm, count(*) as total, " +
                        "sum(case when lower(status)='in_progress' then 1 else 0 end) as in_progress_count, " +
                        "coalesce(min(priority_bucket), 'P3') as top_priority " +
                        "from decision_log group by realm order by realm asc"
                ); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject row = new JsonObject();
                        row.addProperty("alias", alias);
                        row.addProperty("realm", text(rs.getString(1)));
                        row.addProperty("decisionCount", rs.getInt(2));
                        row.addProperty("inProgressCount", rs.getInt(3));
                        row.addProperty("topPriorityBucket", text(rs.getString(4)));
                        decisionSignals.add(row);
                    }
                }
            } catch (Exception ignored) {
                // External DB may not have expected schema; skip safely.
            }
        }

        JsonObject data = new JsonObject();
        data.add("workstreams", workstreams);
        data.add("topInProgressTasks", topInProgressTasks);
        data.add("decisionSignals", decisionSignals);

        JsonObject payload = buildScreenPayload("workstream_status", request, aliases, data);
        return new ReportPayload(payload, aliases.toString());
    }

    private static ReportPayload buildReasoningDrivePayload(String request, List<String> aliases) throws Exception {
        Map<String, AuthorizedDb> auth = authorizedDbMap();
        AuthorizedDb exp = requireAlias(auth, aliases, "reasoning_experience");
        AuthorizedDb drv = requireAlias(auth, aliases, "reasoning_derived");

        JsonArray topHeuristics = new JsonArray();
        JsonArray topInsights = new JsonArray();

        try (Connection conn = connect(Path.of(exp.path))) {
            if (tableExists(conn, "heuristic_scores")) {
                try (PreparedStatement ps = conn.prepareStatement(
                    "select heuristic_key, heuristic_group, score, sample_count, signal from heuristic_scores order by score desc, heuristic_key asc limit 10"
                ); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject row = new JsonObject();
                        row.addProperty("heuristicKey", text(rs.getString(1)));
                        row.addProperty("group", text(rs.getString(2)));
                        row.addProperty("score", round2(rs.getDouble(3)));
                        row.addProperty("sampleCount", rs.getInt(4));
                        row.addProperty("signal", text(rs.getString(5)));
                        topHeuristics.add(row);
                    }
                }
            }
        }

        try (Connection conn = connect(Path.of(drv.path))) {
            if (tableExists(conn, "derived_insights")) {
                try (PreparedStatement ps = conn.prepareStatement(
                    "select insight_id, realm, insight_type, statement, support_score from derived_insights order by support_score desc, insight_id asc limit 10"
                ); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject row = new JsonObject();
                        row.addProperty("insightId", text(rs.getString(1)));
                        row.addProperty("realm", text(rs.getString(2)));
                        row.addProperty("type", text(rs.getString(3)));
                        row.addProperty("statement", text(rs.getString(4)));
                        row.addProperty("supportScore", round2(rs.getDouble(5)));
                        topInsights.add(row);
                    }
                }
            }
        }

        JsonObject data = new JsonObject();
        data.add("topHeuristics", topHeuristics);
        data.add("topInsights", topInsights);

        JsonObject payload = buildScreenPayload("reasoning_drive", request, aliases, data);
        return new ReportPayload(payload, aliases.toString());
    }

    private static ReportPayload buildActionStatusPayload(String request, List<String> aliases) {
        JsonObject data = new JsonObject();
        JsonObject summary = new JsonObject();
        JsonArray owners = new JsonArray();
        JsonArray topSuggestions = new JsonArray();
        JsonObject ingestStrict = new JsonObject();

        JsonObject actions = readJson(ACTION_ITEMS);
        if (actions != null) {
            summary.addProperty("total", str(actions, "itemCount", "0"));
            JsonObject byStatus = actions.getAsJsonObject("byStatus");
            summary.addProperty("open", byStatus == null ? "0" : str(byStatus, "open", "0"));
            int stale = 0;
            JsonElement rows = actions.get("actions");
            if (rows != null && rows.isJsonArray()) {
                for (JsonElement el : rows.getAsJsonArray()) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = el.getAsJsonObject();
                    if (Boolean.parseBoolean(str(row, "isStale", "false"))) {
                        stale++;
                    }
                }
            }
            summary.addProperty("stale", stale);
        } else {
            summary.addProperty("total", "0");
            summary.addProperty("open", "0");
            summary.addProperty("stale", "0");
        }

        JsonObject ownerSummary = readJson(ACTION_OWNER_SUMMARY);
        if (ownerSummary != null) {
            JsonElement rows = ownerSummary.get("owners");
            if (rows != null && rows.isJsonArray()) {
                owners = rows.getAsJsonArray();
            }
        }

        JsonObject suggestions = readJson(ACTION_SUGGESTIONS);
        if (suggestions != null) {
            JsonElement rows = suggestions.get("suggestions");
            int count = 0;
            if (rows != null && rows.isJsonArray()) {
                for (JsonElement el : rows.getAsJsonArray()) {
                    if (count >= 10 || !el.isJsonObject()) {
                        break;
                    }
                    JsonObject row = el.getAsJsonObject();
                    JsonElement suggestionRows = row.get("suggestions");
                    if (suggestionRows == null || !suggestionRows.isJsonArray() || suggestionRows.getAsJsonArray().isEmpty()) {
                        continue;
                    }
                    JsonObject first = suggestionRows.getAsJsonArray().get(0).getAsJsonObject();
                    JsonObject out = new JsonObject();
                    out.addProperty("actionId", str(row, "actionId", ""));
                    out.addProperty("actionRef", str(row, "actionRef", str(row, "actionId", "")));
                    out.addProperty("decisionId", str(first, "decisionId", ""));
                    out.addProperty("decisionRef", str(first, "decisionRef", str(first, "decisionId", "")));
                    out.addProperty("score", str(first, "score", "0"));
                    topSuggestions.add(out);
                    count++;
                }
            }
        }

        JsonObject strict = readJson(ACTION_INGEST_STRICT);
        if (strict != null) {
            ingestStrict.addProperty("status", str(strict, "status", "UNKNOWN"));
            ingestStrict.addProperty("missingCount", str(strict, "missingCount", "0"));
        } else {
            ingestStrict.addProperty("status", "UNKNOWN");
            ingestStrict.addProperty("missingCount", "0");
        }

        data.add("summary", summary);
        data.add("owners", owners);
        data.add("topSuggestions", topSuggestions);
        data.add("ingestStrict", ingestStrict);
        JsonObject payload = buildScreenPayload("action_status", request, aliases, data);
        return new ReportPayload(payload, aliases.toString());
    }

    private static ReportPayload buildRealmKnowledgeStatusPayload(String request, List<String> aliases) {
        JsonObject data = new JsonObject();
        JsonArray realms = new JsonArray();
        JsonObject sync = readJson(REALM_KNOWLEDGE_SYNC);
        if (sync != null) {
            JsonElement targets = sync.get("targets");
            if (targets != null && targets.isJsonArray()) {
                realms = targets.getAsJsonArray();
            }
        }
        JsonObject gate = readJson(REALM_KNOWLEDGE_LINK_GATE);
        if (gate == null) {
            gate = new JsonObject();
            gate.addProperty("overallStatus", "UNKNOWN");
            gate.addProperty("unresolvedTotal", "0");
        }
        data.add("realms", realms);
        data.add("linkGate", gate);
        JsonObject payload = buildScreenPayload("realm_knowledge_status", request, aliases, data);
        return new ReportPayload(payload, aliases.toString());
    }

    private static JsonObject buildScreenPayload(String screenId, String request, List<String> aliases, JsonObject data) {
        JsonObject payload = new JsonObject();
        payload.addProperty("schemaVersion", "1");
        payload.addProperty("exchangeFormat", "pm-console-screen@1");
        payload.addProperty("generatedAt", nowIso());
        payload.addProperty("screenId", screenId);
        payload.addProperty("request", request);
        JsonArray sourceAliases = new JsonArray();
        for (String a : aliases) {
            sourceAliases.add(a);
        }
        payload.add("sourceAliases", sourceAliases);
        payload.add("data", data);
        return payload;
    }

    private static List<String> resolveAliases(String raw) {
        List<String> parsed = splitCsv(raw);
        if (parsed.isEmpty()) {
            return List.of("project", "pm_realm", "boilerplate_realm", "reasoning_experience", "reasoning_derived");
        }
        return parsed;
    }

    private static Map<String, AuthorizedDb> authorizedDbMap() throws Exception {
        ensureAuthorizedDatabasesFile();
        List<AuthorizedDb> list = loadAuthorizedDatabases();
        Map<String, AuthorizedDb> out = new LinkedHashMap<>();
        for (AuthorizedDb db : list) {
            out.put(db.alias, db);
        }
        return out;
    }

    private static AuthorizedDb requireAlias(Map<String, AuthorizedDb> auth, List<String> aliases, String alias) {
        if (!aliases.contains(alias)) {
            throw new IllegalArgumentException("Required alias '" + alias + "' missing. Requested aliases=" + aliases);
        }
        AuthorizedDb db = auth.get(alias);
        if (db == null || !db.enabled) {
            throw new IllegalArgumentException("Alias '" + alias + "' is not authorized/enabled.");
        }
        return db;
    }

    private static void ensureAuthorizedDatabasesFile() throws IOException {
        if (Files.exists(AUTHORIZED_DBS)) {
            return;
        }
        ensureParent(AUTHORIZED_DBS);
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", "1");
        root.addProperty("generatedAt", nowIso());
        root.addProperty("notes", "Only aliases in this file are allowed for report federation.");
        JsonArray dbs = new JsonArray();
        dbs.add(dbAsJson(new AuthorizedDb("project", PROJECT_DB.toString().replace('\\', '/'), true, true, "Project PM state database")));
        dbs.add(dbAsJson(new AuthorizedDb("boilerplate", BOILERPLATE_DB.toString().replace('\\', '/'), true, true, "Boilerplate PM state database")));
        dbs.add(dbAsJson(new AuthorizedDb("application_realm", "pm/state/application-realm.sqlite", true, true, "Application realm SQL authority")));
        dbs.add(dbAsJson(new AuthorizedDb("pm_realm", "pm/state/pm-realm.sqlite", true, true, "PM realm SQL authority")));
        dbs.add(dbAsJson(new AuthorizedDb("boilerplate_realm", "pm/state/boilerplate-realm.sqlite", true, true, "Boilerplate realm SQL authority")));
        dbs.add(dbAsJson(new AuthorizedDb("reasoning_experience", "pm/state/reasoning-experience.sqlite", true, true, "Empirical reasoning event database")));
        dbs.add(dbAsJson(new AuthorizedDb("reasoning_derived", "pm/state/reasoning-derived.sqlite", true, true, "Derived insight database")));
        root.add("databases", dbs);
        Files.writeString(AUTHORIZED_DBS, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
    }

    private static List<AuthorizedDb> loadAuthorizedDatabases() throws IOException {
        JsonObject root = readJson(AUTHORIZED_DBS);
        if (root == null) {
            return List.of();
        }
        JsonElement dbs = root.get("databases");
        if (dbs == null || !dbs.isJsonArray()) {
            return List.of();
        }
        List<AuthorizedDb> out = new ArrayList<>();
        for (JsonElement el : dbs.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            out.add(new AuthorizedDb(
                text(str(obj, "alias", "")),
                text(str(obj, "path", "")),
                Boolean.parseBoolean(str(obj, "enabled", "true")),
                Boolean.parseBoolean(str(obj, "readOnly", "true")),
                text(str(obj, "description", ""))
            ));
        }
        return out;
    }

    private static void saveAuthorizedDatabases(List<AuthorizedDb> dbs) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", "1");
        root.addProperty("generatedAt", nowIso());
        root.addProperty("notes", "Only aliases in this file are allowed for report federation.");
        JsonArray arr = new JsonArray();
        for (AuthorizedDb db : dbs) {
            arr.add(dbAsJson(db));
        }
        root.add("databases", arr);
        ensureParent(AUTHORIZED_DBS);
        Files.writeString(AUTHORIZED_DBS, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
    }

    private static JsonObject dbAsJson(AuthorizedDb db) {
        JsonObject obj = new JsonObject();
        obj.addProperty("alias", db.alias);
        obj.addProperty("path", db.path);
        obj.addProperty("enabled", db.enabled);
        obj.addProperty("readOnly", db.readOnly);
        obj.addProperty("description", db.description);
        return obj;
    }

    private static int runWorkflowPhase(String phase, boolean dryRun) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("python3");
        cmd.add("tools/pm_workflow.py");
        cmd.add("run");
        cmd.add("--adapter");
        cmd.add("gradle");
        cmd.add("--phase");
        cmd.add(phase);
        if (dryRun) {
            System.out.println("dry-run: " + String.join(" ", cmd));
            return 0;
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Path.of(".").toFile());
        pb.inheritIO();
        Process p = pb.start();
        return p.waitFor();
    }

    private static int runGradleTasks(List<String> tasks, boolean dryRun) throws Exception {
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("./gradlew");
        cmd.add("--no-daemon");
        cmd.addAll(tasks);
        if (dryRun) {
            System.out.println("dry-run: " + String.join(" ", cmd));
            return 0;
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Path.of(".").toFile());
        pb.inheritIO();
        Process p = pb.start();
        return p.waitFor();
    }

    private static Connection connect(Path dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private static boolean tableExists(Connection conn, String tableName) {
        try (PreparedStatement ps = conn.prepareStatement("select name from sqlite_master where type='table' and name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
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

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String t = text(token);
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String normalizePriority(String value) {
        String normalized = text(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "high", "medium", "low" -> normalized;
            default -> "";
        };
    }

    private static void appendPrompt(String text) throws IOException {
        Path parent = AI_INBOX.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("capturedAt", nowIso());
        payload.addProperty("source", "pmconsole-live");
        payload.addProperty("prompt", text);
        Files.writeString(
            AI_INBOX,
            payload + "\n",
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

    static LiveActionSelection parseLiveActionsSelection(String command) {
        int top = 10;
        String priority = "";
        boolean staleOnly = false;
        String owner = "";
        boolean openOnly = false;
        String idMode = "friendly";
        String[] tokens = text(command).split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String token = text(tokens[i]).toLowerCase(Locale.ROOT);
            if (token.isBlank()) {
                continue;
            }
            if ("open".equals(token) || "open-only".equals(token)) {
                openOnly = true;
                continue;
            }
            if ("stale".equals(token) || "stale-only".equals(token)) {
                staleOnly = true;
                continue;
            }
            if ("high".equals(token) || "medium".equals(token) || "low".equals(token)) {
                priority = token;
                continue;
            }
            if ("raw".equals(token) || "id-raw".equals(token)) {
                idMode = "raw";
                continue;
            }
            if ("friendly".equals(token) || "id-friendly".equals(token)) {
                idMode = "friendly";
                continue;
            }
            if ("owner".equals(token) && i + 1 < tokens.length) {
                owner = text(tokens[++i]);
                continue;
            }
            try {
                top = Math.max(1, Integer.parseInt(token));
            } catch (NumberFormatException ignored) {
                // ignore non-numeric tokens.
            }
        }
        return new LiveActionSelection(top, priority, staleOnly, owner, openOnly, idMode);
    }

    private static String normalizeIdMode(String raw) {
        String value = text(raw).toLowerCase(Locale.ROOT);
        return "raw".equals(value) ? "raw" : "friendly";
    }

    private static int applyTopSuggestion(String actionIdFilter, boolean dryRun) throws Exception {
        JsonObject root = readJson(ACTION_SUGGESTIONS);
        if (root == null) {
            System.out.println("missing suggestions report: " + ACTION_SUGGESTIONS);
            return 1;
        }
        JsonElement rows = root.get("suggestions");
        if (rows == null || !rows.isJsonArray()) {
            System.out.println("no suggestions found.");
            return 1;
        }

        String actionFilter = text(actionIdFilter);
        String selectedActionId = "";
        String selectedDecisionId = "";
        String selectedDecisionRealm = "";
        for (JsonElement el : rows.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject row = el.getAsJsonObject();
            String actionId = text(str(row, "actionId", ""));
            if (!actionFilter.isBlank() && !actionFilter.equals(actionId)) {
                continue;
            }
            JsonElement suggestions = row.get("suggestions");
            if (suggestions == null || !suggestions.isJsonArray() || suggestions.getAsJsonArray().isEmpty()) {
                continue;
            }
            JsonObject top = suggestions.getAsJsonArray().get(0).getAsJsonObject();
            selectedActionId = actionId;
            selectedDecisionId = text(str(top, "decisionId", ""));
            selectedDecisionRealm = text(str(top, "realm", ""));
            break;
        }

        if (selectedActionId.isBlank() || selectedDecisionId.isBlank()) {
            System.out.println("no applicable suggestion found" + (actionFilter.isBlank() ? "" : " for action " + actionFilter));
            return 1;
        }

        if (dryRun) {
            System.out.println("dry-run: action " + selectedActionId + " -> decision " + selectedDecisionId + " (realm=" + selectedDecisionRealm + ")");
            return 0;
        }

        try (Connection conn = connect(PROJECT_DB)) {
            try (PreparedStatement ps = conn.prepareStatement(
                "insert or replace into action_item_decision_links(action_id, realm, decision_id, relation, linked_at) values(?,?,?,?,?)"
            )) {
                ps.setString(1, selectedActionId);
                ps.setString(2, selectedDecisionRealm);
                ps.setString(3, selectedDecisionId);
                ps.setString(4, "implements");
                ps.setString(5, nowIso());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "update action_items set decision_id=?, updated_at=? where action_id=?"
            )) {
                ps.setString(1, selectedDecisionId);
                ps.setString(2, nowIso());
                ps.setString(3, selectedActionId);
                ps.executeUpdate();
            }
        }

        System.out.println("applied suggestion: action " + selectedActionId + " -> decision " + selectedDecisionId + " (realm=" + selectedDecisionRealm + ")");
        return 0;
    }

    private static void printLiveHelp() {
        System.out.println("pmconsole live commands:");
        System.out.println("  - help");
        System.out.println("  - status");
        System.out.println("  - decisions [n] [raw|friendly]");
        System.out.println("  - actions [n] [high|medium|low] [stale] [open] [owner <name>] [raw|friendly]");
        System.out.println("  - apply-suggestion [action-id] [dry-run]");
        System.out.println("  - report <request>  (e.g., action status)");
        System.out.println("  - refresh");
        System.out.println("  - refresh phase <id>");
        System.out.println("  - refresh tasks <task1,task2>");
        System.out.println("  - refresh preview");
        System.out.println("  - db list");
        System.out.println("  - tools");
        System.out.println("  - interval <seconds> (stored for wrapper use)");
        System.out.println("  - prompt <text>  (alias: ask <text>)");
        System.out.println("  - clear");
        System.out.println("  - quit");
        System.out.println();
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString();
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private record AuthorizedDb(String alias, String path, boolean enabled, boolean readOnly, String description) {}

    private record ReportPayload(JsonObject payload, String sourcesSummary) {}

    static record LiveActionSelection(int top, String priority, boolean staleOnly, String owner, boolean openOnly, String idMode) {}
}
