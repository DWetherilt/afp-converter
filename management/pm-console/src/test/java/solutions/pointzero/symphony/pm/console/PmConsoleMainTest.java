package solutions.pointzero.symphony.pm.console;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmConsoleMainTest {

    @Test
    void parsesLiveActionsCommandWithAllSelectors() {
        PmConsoleMain.LiveActionSelection parsed = PmConsoleMain.parseLiveActionsSelection("actions 7 high stale open owner ai-agent");
        assertEquals(7, parsed.top());
        assertEquals("high", parsed.priority());
        assertTrue(parsed.staleOnly());
        assertEquals("ai-agent", parsed.owner());
        assertTrue(parsed.openOnly());
        assertEquals("friendly", parsed.idMode());
    }

    @Test
    void defaultsLiveActionsSelectionWhenNoArgumentsProvided() {
        PmConsoleMain.LiveActionSelection parsed = PmConsoleMain.parseLiveActionsSelection("actions");
        assertEquals(10, parsed.top());
        assertEquals("", parsed.priority());
        assertFalse(parsed.staleOnly());
        assertEquals("", parsed.owner());
        assertFalse(parsed.openOnly());
        assertEquals("friendly", parsed.idMode());
    }

    @Test
    void parsesLiveActionsCommandWithRawIdMode() {
        PmConsoleMain.LiveActionSelection parsed = PmConsoleMain.parseLiveActionsSelection("actions 5 raw");
        assertEquals(5, parsed.top());
        assertEquals("raw", parsed.idMode());
    }

    @Test
    void reportCommandSupportsRealmKnowledgeStatus() throws Exception {
        Path out = Files.createTempFile("pmconsole-realm-knowledge-status-", ".json");
        try {
            PmConsoleMain.ReportCommand cmd = new PmConsoleMain.ReportCommand();
            cmd.request = "realm knowledge status";
            cmd.format = "json";
            cmd.output = out.toString();
            int exit = cmd.call();
            assertEquals(0, exit);
            String raw = Files.readString(out, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            assertEquals("realm_knowledge_status", obj.get("screenId").getAsString());
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void reportCommandSupportsGovernanceStatus() throws Exception {
        Path out = Files.createTempFile("pmconsole-governance-status-", ".json");
        try {
            PmConsoleMain.ReportCommand cmd = new PmConsoleMain.ReportCommand();
            cmd.request = "governance status";
            cmd.format = "json";
            cmd.output = out.toString();
            int exit = cmd.call();
            assertEquals(0, exit);
            String raw = Files.readString(out, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            assertEquals("governance_status", obj.get("screenId").getAsString());
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
