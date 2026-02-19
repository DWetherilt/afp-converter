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
                "('pm_data_dictionary', 'pm/policy/policy-rule-tables.sql')");
            st.execute("insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values " +
                "('PM-COMMS-001', 'pm', 'communication-governance', 'Assistant responses must include next 10 tasks.', 'pm/policy/policy-rule-tables.sql', 'human', 1, '2026-02-19T00:00:00Z')");
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
                "('pm_data_dictionary', 'pm/policy/policy-rule-tables.sql'), " +
                "('pm_workflow_manifest', 'pm/workflow/workflow-manifest.json')");
            st.execute("insert into policy_rule_catalog(rule_id, realm, category, rule_text, source_ref, mutable_by, enabled, updated_at) values " +
                "('PM-X-001', 'pm', 'test', 'Must align with pm_workflow_manifest', 'pm/workflow/workflow-manifest.json', 'human', 1, '2026-02-19T00:00:00Z')");
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
}
