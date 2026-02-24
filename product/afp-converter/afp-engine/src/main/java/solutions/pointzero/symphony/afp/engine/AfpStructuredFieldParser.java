package solutions.pointzero.symphony.afp.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

final class AfpStructuredFieldParser {
    private static final int SF_START = 0x5A;
    private static final int MIN_SF_LENGTH = 8;

    AfpParseResult parse(byte[] bytes) {
        List<AfpStructuredField> fields = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int cursor = 0;
        int skipped = 0;
        boolean truncated = false;

        while (cursor < bytes.length) {
            int next = findNextStructuredField(bytes, cursor);
            if (next < 0) {
                skipped += bytes.length - cursor;
                break;
            }
            skipped += Math.max(0, next - cursor);
            cursor = next;

            if (cursor + 2 >= bytes.length) {
                warnings.add("Truncated structured field introducer at offset " + cursor);
                truncated = true;
                break;
            }

            int length = readUnsignedShort(bytes, cursor + 1);
            if (length < MIN_SF_LENGTH) {
                warnings.add("Invalid structured field length " + length + " at offset " + cursor);
                cursor += 1;
                continue;
            }

            int totalLength = 1 + length;
            if (cursor + totalLength > bytes.length) {
                warnings.add("Structured field overruns input at offset " + cursor + " length " + length);
                truncated = true;
                break;
            }

            String sfIdHex = HexFormat.of().withUpperCase().formatHex(bytes, cursor + 3, cursor + 6);
            int flags = bytes[cursor + 6] & 0xFF;
            int payloadLength = Math.max(0, totalLength - 9);
            byte[] payload = payloadLength == 0
                ? new byte[0]
                : Arrays.copyOfRange(bytes, cursor + 9, cursor + 9 + payloadLength);

            fields.add(new AfpStructuredField(cursor, length, sfIdHex, flags, payloadLength, payload));
            cursor += totalLength;
        }

        return new AfpParseResult(List.copyOf(fields), List.copyOf(warnings), skipped, truncated);
    }

    private static int readUnsignedShort(byte[] bytes, int index) {
        return ((bytes[index] & 0xFF) << 8) | (bytes[index + 1] & 0xFF);
    }

    private static int findNextStructuredField(byte[] bytes, int start) {
        for (int i = start; i < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == SF_START) {
                return i;
            }
        }
        return -1;
    }
}
