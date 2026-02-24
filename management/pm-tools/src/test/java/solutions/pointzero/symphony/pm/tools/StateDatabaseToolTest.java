package solutions.pointzero.symphony.pm.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateDatabaseToolTest {

    @Test
    void lintDataDictionaryFailsWhenRequiredObjectsMissing(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("state.sqlite");
        Path out = tempDir.resolve("lint.json");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("create table pm_data_dictionary(object_name text primary key)");
            st.execute("insert into pm_data_dictionary(object_name) values ('issues')");
        }

        int exit = StateDatabaseTool.execute(new String[] {
            "lint-data-dictionary",
            "--db", db.toString(),
            "--output", out.toString(),
            "--required", "issues,pm_workflow_manifest"
        });
        assertEquals(2, exit);

        JsonObject json = JsonParser.parseString(Files.readString(out, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(false, json.get("ok").getAsBoolean());
        assertEquals(1, json.get("missingCount").getAsInt());
        assertTrue(json.get("missingObjects").toString().contains("pm_workflow_manifest"));
    }

    @Test
    void exportPolicyRulesIncludesPmCommsRule(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("state.sqlite");
        Path out = tempDir.resolve("policy-rules.json");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("create table policy_rule_catalog(" +
                "rule_id text primary key, realm text, category text, rule_text text, source_ref text, mutable_by text, enabled integer, updated_at text)");
            st.execute("create table pm_data_dictionary(" +
                "object_name text primary key, object_type text, realm text, definition text, source_ref text, naming_pattern text, updated_at text)");
            st.execute("insert into pm_data_dictionary(object_name, source_ref) values " +
                "('pm_data_dictionary', 'management/pm/policy/policy-rule-tables.sql')");
            st.execute("insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values " +
                "('PM-COMMS-001', 'pm', 'communication-governance', 'Assistant responses must include next 10 tasks.', 'management/pm/policy/policy-rule-tables.sql', 'human', 1, '2026-02-19T00:00:00Z')");
        }

        int exit = StateDatabaseTool.execute(new String[] {
            "export-policy-rules",
            "--db", db.toString(),
            "--json", out.toString()
        });
        assertEquals(0, exit);

        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"PM-COMMS-001\""));
        assertTrue(payload.contains("\"dictionaryRefs\""));
    }

    @Test
    void lintPolicyRulesFailsWhenPmCommsRuleMissing(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("state.sqlite");
        Path out = tempDir.resolve("policy-rules-lint.json");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("create table policy_rule_catalog(" +
                "rule_id text primary key, realm text, category text, rule_text text, source_ref text, mutable_by text, enabled integer, updated_at text)");
            st.execute("insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values " +
                "('PM-OTHER-001', 'pm', 'example', 'placeholder', 'x', 'human', 1, '2026-02-19T00:00:00Z')");
        }

        int exit = StateDatabaseTool.execute(new String[] {
            "lint-policy-rules",
            "--db", db.toString(),
            "--output", out.toString()
        });
        assertEquals(2, exit);
        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"missingRuleIds\""));
        assertTrue(payload.contains("PM-COMMS-001"));
    }

    @Test
    void exportPolicyRulesResolvesDictionaryRefsByObjectNameAndSourceRef(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("state.sqlite");
        Path out = tempDir.resolve("policy-rules.json");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("create table policy_rule_catalog(" +
                "rule_id text primary key, realm text, category text, rule_text text, source_ref text, mutable_by text, enabled integer, updated_at text)");
            st.execute("create table pm_data_dictionary(" +
                "object_name text primary key, object_type text, realm text, definition text, source_ref text, naming_pattern text, updated_at text)");
            st.execute("insert into pm_data_dictionary(object_name, source_ref) values " +
                "('pm_data_dictionary', 'management/pm/policy/policy-rule-tables.sql'), " +
                "('pm_workflow_manifest', 'management/pm/workflow/workflow-manifest.json')");
            st.execute("insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values " +
                "('PM-X-001', 'pm', 'test', 'Must align with pm_workflow_manifest', 'management/pm/workflow/workflow-manifest.json', 'human', 1, '2026-02-19T00:00:00Z')");
        }

        int exit = StateDatabaseTool.execute(new String[] {
            "export-policy-rules",
            "--db", db.toString(),
            "--json", out.toString()
        });
        assertEquals(0, exit);
        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"dictionaryRefs\""));
        assertTrue(payload.contains("pm_workflow_manifest"));
    }

    @Test
    void governanceAlertsFlagsMissingRequiredPolicyRule(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("state.sqlite");
        Path out = tempDir.resolve("governance-alerts.json");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("create table governance_events(" +
                "event_id text primary key, event_date text, phase text, status text, checkpoint_id text, issue_id text, summary text, evidence text, updated_by text, updated_at text)");
            st.execute("create table policy_rule_catalog(" +
                "rule_id text primary key, realm text, category text, rule_text text, source_ref text, mutable_by text, enabled integer, updated_at text)");
            st.execute("insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values " +
                "('PM-OTHER-001', 'pm', 'test', 'placeholder', 'x', 'human', 1, '2026-02-19T00:00:00Z')");
        }

        int exit = StateDatabaseTool.execute(new String[] {
            "governance-alerts",
            "--db", db.toString(),
            "--output", out.toString(),
            "--enforce"
        });
        assertEquals(2, exit);
        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"policyRuleAudit\""));
        assertTrue(payload.contains("\"requiredRuleEnabled\": false"));
    }

    @Test
    void syncCodeIndexAndSearchByTokenAcrossRealmDb(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("pkg"));
        Files.writeString(
            src.resolve("pkg/Sample.java"),
            "package pkg; class Sample { void runRenderToken() { String value = \"render optimize\"; } }",
            StandardCharsets.UTF_8
        );

        Path db = tempDir.resolve("application-realm.sqlite");
        Path search = tempDir.resolve("token-search.json");
        int syncExit = StateDatabaseTool.execute(new String[] {
            "sync-code-index",
            "--db", db.toString(),
            "--realm", "application",
            "--roots", src.toString()
        });
        assertEquals(0, syncExit);

        int searchExit = StateDatabaseTool.execute(new String[] {
            "search-code-token",
            "--db", db.toString(),
            "--keyword", "render",
            "--output", search.toString()
        });
        assertEquals(0, searchExit);

        String payload = Files.readString(search, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"dictionaryMatches\""));
        assertTrue(payload.contains("\"fileHits\""));
        assertTrue(payload.contains("Sample.java"));
        assertTrue(payload.contains("render"));
    }

    @Test
    void upsertAndMaterializeCodeFileRoundTrip(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("pm-realm.sqlite");
        Path source = tempDir.resolve("snippet.java");
        Files.writeString(source, "class SqlManaged { String v = \"tokenized\"; }", StandardCharsets.UTF_8);

        int upsertExit = StateDatabaseTool.execute(new String[] {
            "upsert-code-file",
            "--db", db.toString(),
            "--realm", "pm",
            "--endpoint", "tmp/sql-managed/Snippet.java",
            "--source", source.toString()
        });
        assertEquals(0, upsertExit);

        Path outRoot = tempDir.resolve("out");
        int materializeExit = StateDatabaseTool.execute(new String[] {
            "materialize-code-realm",
            "--db", db.toString(),
            "--realm", "pm",
            "--target-root", outRoot.toString()
        });
        assertEquals(0, materializeExit);

        Path materialized = outRoot.resolve("tmp/sql-managed/Snippet.java");
        assertTrue(Files.exists(materialized));
        String value = Files.readString(materialized, StandardCharsets.UTF_8);
        assertTrue(value.contains("SqlManaged"));
        assertTrue(value.contains("tokenized"));
    }

    @Test
    void upsertManifestAppliesMultipleFilesTransactionally(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("application.sqlite");
        Path src1 = tempDir.resolve("A.java");
        Path src2 = tempDir.resolve("B.java");
        Files.writeString(src1, "class A {}", StandardCharsets.UTF_8);
        Files.writeString(src2, "class B {}", StandardCharsets.UTF_8);
        Path manifest = tempDir.resolve("patch.json");
        Files.writeString(
            manifest,
            "{ \"files\": [" +
                "{ \"endpoint\": \"tmp/sql/A.java\", \"source\": \"" + src1.toString().replace("\\", "\\\\") + "\" }," +
                "{ \"endpoint\": \"tmp/sql/B.java\", \"source\": \"" + src2.toString().replace("\\", "\\\\") + "\" }" +
                "] }",
            StandardCharsets.UTF_8
        );

        int exit = StateDatabaseTool.execute(new String[] {
            "upsert-code-files-manifest",
            "--db", db.toString(),
            "--realm", "application",
            "--manifest", manifest.toString()
        });
        assertEquals(0, exit);

        Path outRoot = tempDir.resolve("out");
        int materializeExit = StateDatabaseTool.execute(new String[] {
            "materialize-code-realm",
            "--db", db.toString(),
            "--realm", "application",
            "--target-root", outRoot.toString()
        });
        assertEquals(0, materializeExit);
        assertTrue(Files.exists(outRoot.resolve("tmp/sql/A.java")));
        assertTrue(Files.exists(outRoot.resolve("tmp/sql/B.java")));
    }

    @Test
    void verifyCodeRealmDetectsFilesystemDrift(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("pm.sqlite");
        Path source = tempDir.resolve("Sample.java");
        Files.writeString(source, "class Sample {}", StandardCharsets.UTF_8);
        int upsertExit = StateDatabaseTool.execute(new String[] {
            "upsert-code-file",
            "--db", db.toString(),
            "--realm", "pm",
            "--endpoint", "tmp/managed/Sample.java",
            "--source", source.toString()
        });
        assertEquals(0, upsertExit);

        Path root = tempDir.resolve("root");
        StateDatabaseTool.execute(new String[] {
            "materialize-code-realm",
            "--db", db.toString(),
            "--realm", "pm",
            "--target-root", root.toString()
        });
        Files.writeString(root.resolve("tmp/managed/Sample.java"), "class Sample { int drift = 1; }", StandardCharsets.UTF_8);

        Path report = tempDir.resolve("drift.json");
        int verifyExit = StateDatabaseTool.execute(new String[] {
            "verify-code-realm",
            "--db", db.toString(),
            "--realm", "pm",
            "--target-root", root.toString(),
            "--output", report.toString(),
            "--enforce"
        });
        assertEquals(2, verifyExit);
        String payload = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"ok\": false"));
        assertTrue(payload.contains("\"mismatchCount\": 1"));
    }

    @Test
    void exportBoilerplatePromotionReportSummarizesStatuses(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("boilerplate-state.sqlite");
        Path out = tempDir.resolve("promotion-report.json");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("create table package_candidates(" +
                "candidate_id text primary key, realm text, package_id text, target_repo text, source_repo text, " +
                "created_at text, summary text, package_zip text, patch_count integer, manifest_file_count integer, " +
                "promotion_status text, promoted_at text, promotion_notes text, supersedes_json text, updated_at text)");
            st.execute("insert into package_candidates(candidate_id, realm, package_id, target_repo, source_repo, created_at, summary, package_zip, patch_count, manifest_file_count, promotion_status, promoted_at, promotion_notes, supersedes_json, updated_at) values " +
                "('pkg-a', 'boilerplate', 'pkg-a', 'pz-boilerplate-intelliJ', 'afp-converter', '2026-02-19T00:00:00Z', 'A', 'pkg-a.zip', 1, 3, 'approved', '', '', '[]', '2026-02-19T00:00:00Z')," +
                "('pkg-b', 'boilerplate', 'pkg-b', 'pz-boilerplate-intelliJ', 'afp-converter', '2026-02-19T00:00:00Z', 'B', '', 0, 2, 'promoted', '2026-02-19T12:00:00Z', 'merged', '[\"pkg-a\"]', '2026-02-19T00:00:00Z')");
        }

        int exit = StateDatabaseTool.execute(new String[] {
            "export-boilerplate-promotion-report",
            "--db", db.toString(),
            "--json", out.toString()
        });
        assertEquals(0, exit);
        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"statusCounts\""));
        assertTrue(payload.contains("\"approved\": 1"));
        assertTrue(payload.contains("\"promoted\": 1"));
        assertTrue(payload.contains("\"recommendedAction\""));
    }

    @Test
    void upsertDecisionAndExportCrossRealmPriorityQueue(@TempDir Path tempDir) throws Exception {
        Path appDb = tempDir.resolve("application.sqlite");
        Path pmDb = tempDir.resolve("pm.sqlite");
        Path boilerDb = tempDir.resolve("boilerplate.sqlite");
        Path out = tempDir.resolve("decision-priority.json");

        int appUpsert = StateDatabaseTool.execute(new String[] {
            "upsert-decision",
            "--db", appDb.toString(),
            "--realm", "application",
            "--decision-id", "APP-1",
            "--title", "Close graphics parity gap",
            "--scope-level", "component",
            "--scope-ref", "afp-engine",
            "--status", "in_progress",
            "--risk-score", "3.5",
            "--blast-radius", "4.5",
            "--unblock-factor", "3.5",
            "--confidence", "4.0",
            "--value-density", "4.5"
        });
        assertEquals(0, appUpsert);

        int boilerUpsert = StateDatabaseTool.execute(new String[] {
            "upsert-decision",
            "--db", boilerDb.toString(),
            "--realm", "boilerplate",
            "--decision-id", "BP-1",
            "--title", "Promote package checksum guard",
            "--scope-level", "sub_boilerplate",
            "--scope-ref", "pz-boilerplate-intelliJ",
            "--sub-scope-ref", ".github/workflows/ci.yml",
            "--status", "proposed",
            "--risk-score", "4.0",
            "--blast-radius", "3.0",
            "--unblock-factor", "4.0",
            "--confidence", "4.0",
            "--value-density", "4.0"
        });
        assertEquals(0, boilerUpsert);

        int export = StateDatabaseTool.execute(new String[] {
            "export-decision-priority",
            "--application-db", appDb.toString(),
            "--pm-db", pmDb.toString(),
            "--boilerplate-db", boilerDb.toString(),
            "--json", out.toString()
        });
        assertEquals(0, export);

        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"candidateCount\": 2"));
        assertTrue(payload.contains("\"decisionId\": \"APP-1\""));
        assertTrue(payload.contains("\"decisionId\": \"BP-1\""));
        assertTrue(payload.contains("\"scopeLevel\": \"sub_boilerplate\""));
        assertTrue(payload.contains("\"byRealm\""));
    }

    @Test
    void upsertKnowledgeWithEvidenceAndDecisionLinkExports(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("project-state.sqlite");
        Path artifact = tempDir.resolve("artifact.json");
        Path out = tempDir.resolve("knowledge-base.json");
        Files.writeString(artifact, "{\"ok\":true}\n", StandardCharsets.UTF_8);

        int upsert = StateDatabaseTool.execute(new String[] {
            "upsert-knowledge",
            "--db", db.toString(),
            "--knowledge-id", "KB-1",
            "--realm", "application",
            "--scope-level", "component",
            "--scope-ref", "afp-engine",
            "--title", "Renderer drift insight",
            "--reasoning", "Observed graphics drift in strict mode.",
            "--context-snapshot", "Compared output against IBM sample.",
            "--outcome-status", "open",
            "--outcome-summary", "Needs follow-up",
            "--confidence", "4.0",
            "--impact-score", "4.5",
            "--change-ref", "management/docs/project-plan-progress.csv"
        });
        assertEquals(0, upsert);

        int evidence = StateDatabaseTool.execute(new String[] {
            "add-knowledge-evidence",
            "--db", db.toString(),
            "--knowledge-id", "KB-1",
            "--artifact-path", artifact.toString(),
            "--type", "report",
            "--notes", "fidelity evidence"
        });
        assertEquals(0, evidence);

        int link = StateDatabaseTool.execute(new String[] {
            "link-knowledge-decision",
            "--db", db.toString(),
            "--knowledge-id", "KB-1",
            "--realm", "application",
            "--decision-id", "APP-RENDER-001",
            "--relation", "supports"
        });
        assertEquals(0, link);

        int export = StateDatabaseTool.execute(new String[] {
            "export-knowledge-base",
            "--db", db.toString(),
            "--json", out.toString()
        });
        assertEquals(0, export);

        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"entryCount\": 1"));
        assertTrue(payload.contains("\"knowledgeId\": \"KB-1\""));
        assertTrue(payload.contains("\"artifactPath\""));
        assertTrue(payload.contains("\"decisionId\": \"APP-RENDER-001\""));
    }

    @Test
    void exportActionItemsIncludesHumanReadableActionRef(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("project-state.sqlite");
        Path out = tempDir.resolve("actions.json");

        int upsert = StateDatabaseTool.execute(new String[] {
            "upsert-action-item",
            "--db", db.toString(),
            "--action-id", "ACT-REF-001",
            "--realm", "pm",
            "--title", "Ensure naming lint is enforced",
            "--status", "open",
            "--priority", "high"
        });
        assertEquals(0, upsert);

        int export = StateDatabaseTool.execute(new String[] {
            "export-action-items",
            "--db", db.toString(),
            "--json", out.toString()
        });
        assertEquals(0, export);

        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"actionId\": \"ACT-REF-001\""));
        assertTrue(payload.contains("\"actionRef\": \"Action | "));
    }

    @Test
    void exportDecisionPriorityIncludesHumanReadableDecisionRef(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("pm-realm.sqlite");
        Path out = tempDir.resolve("decisions.json");

        int upsert = StateDatabaseTool.execute(new String[] {
            "upsert-decision",
            "--db", db.toString(),
            "--realm", "pm",
            "--decision-id", "PM-DEC-20260219-007",
            "--title", "Adopt presentation naming policy",
            "--status", "proposed",
            "--risk-score", "3.0",
            "--blast-radius", "2.0",
            "--unblock-factor", "4.0",
            "--confidence", "4.0",
            "--value-density", "4.0"
        });
        assertEquals(0, upsert);

        int export = StateDatabaseTool.execute(new String[] {
            "export-decision-priority",
            "--pm-db", db.toString(),
            "--json", out.toString()
        });
        assertEquals(0, export);

        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"decisionId\": \"PM-DEC-20260219-007\""));
        assertTrue(payload.contains("\"decisionRef\": \"Decision | 2026-02-19 | #007\""));
    }

    @Test
    void exportIdentifierRegistryIncludesPersistedActionIdentifier(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("project-state.sqlite");
        Path out = tempDir.resolve("identifier-registry.json");

        int upsert = StateDatabaseTool.execute(new String[] {
            "upsert-action-item",
            "--db", db.toString(),
            "--action-id", "ACT-ID-20260219-001",
            "--realm", "pm",
            "--title", "Persist identifier registry rows",
            "--status", "open",
            "--priority", "high"
        });
        assertEquals(0, upsert);

        int export = StateDatabaseTool.execute(new String[] {
            "export-identifier-registry",
            "--db", db.toString(),
            "--json", out.toString()
        });
        assertEquals(0, export);

        String payload = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"identifier\": \"ACT-ID-20260219-001\""));
        assertTrue(payload.contains("\"identifierKind\": \"action_id\""));
    }
}
