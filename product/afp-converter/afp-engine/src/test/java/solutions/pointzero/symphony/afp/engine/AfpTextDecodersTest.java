package solutions.pointzero.symphony.afp.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfpTextDecodersTest {
    @Test
    void decodesMixedShiftRunsIntoOrderedSegments() {
        byte[] payload = new byte[] {
            0x41, 0x42, // SBCS: AB
            0x0E,       // SO (enter DBCS)
            0x42, 0x43, 0x44, 0x45, // DBCS bytes (two pairs)
            0x0F,       // SI (return SBCS)
            0x43, 0x44  // SBCS: CD
        };
        AfpCodePageProfile profile = new AfpCodePageProfile(
            Charset.forName("ISO-8859-1"),
            Charset.forName("x-IBM939"),
            "test"
        );

        List<String> runs = AfpTextDecoders.decodeTextRuns(payload, profile);

        assertEquals(3, runs.size(), "Expected SBCS/DBCS/SBCS run segmentation");
        assertTrue(runs.get(0).contains("AB"), "Expected first SBCS run to preserve leading text");
        assertTrue(runs.get(2).contains("CD"), "Expected last SBCS run to preserve trailing text");
    }
}
