package solutions.pointzero.symphony.pm.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmConsoleMainTest {

    @Test
    void parsesLiveActionsCommandWithAllSelectors() {
        PmConsoleMain.LiveActionSelection parsed = PmConsoleMain.parseLiveActionsSelection("actions 7 high stale");
        assertEquals(7, parsed.top());
        assertEquals("high", parsed.priority());
        assertTrue(parsed.staleOnly());
    }

    @Test
    void defaultsLiveActionsSelectionWhenNoArgumentsProvided() {
        PmConsoleMain.LiveActionSelection parsed = PmConsoleMain.parseLiveActionsSelection("actions");
        assertEquals(10, parsed.top());
        assertEquals("", parsed.priority());
        assertFalse(parsed.staleOnly());
    }
}
