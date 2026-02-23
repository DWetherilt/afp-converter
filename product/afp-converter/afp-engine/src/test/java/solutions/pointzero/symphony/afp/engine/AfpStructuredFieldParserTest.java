package solutions.pointzero.symphony.afp.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfpStructuredFieldParserTest {
    @Test
    void parsesStructuredFieldsAndSkipsNoise() {
        byte[] input = new byte[] {
            0x00, 0x01, // noise
            0x5A, 0x00, 0x08, (byte) 0xD3, (byte) 0xA8, (byte) 0xAF, 0x00, 0x00, 0x00,
            0x11,
            0x5A, 0x00, 0x08, (byte) 0xD3, (byte) 0xA9, (byte) 0xAF, 0x10, 0x00, 0x00
        };

        AfpStructuredFieldParser parser = new AfpStructuredFieldParser();
        AfpParseResult result = parser.parse(input);

        assertEquals(2, result.fields().size());
        assertEquals(3, result.skippedBytes());
        assertFalse(result.truncated());
        assertEquals("D3A8AF", result.fields().getFirst().sfIdHex());
        assertEquals("D3A9AF", result.fields().get(1).sfIdHex());
    }

    @Test
    void flagsTruncatedField() {
        byte[] input = new byte[] {
            0x5A, 0x00, 0x10, (byte) 0xD3, (byte) 0xA8, (byte) 0xAF, 0x00, 0x00, 0x00, 0x01
        };

        AfpStructuredFieldParser parser = new AfpStructuredFieldParser();
        AfpParseResult result = parser.parse(input);

        assertTrue(result.truncated());
        assertTrue(result.warnings().stream().anyMatch(message -> message.contains("overruns input")));
        assertEquals(0, result.fields().size());
    }
}
