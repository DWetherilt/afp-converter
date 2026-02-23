package solutions.pointzero.symphony.afp.engine;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AfpTextDecoders {
    private static final int PTOCA_CS_INTRODUCER = 0x2B;
    private static final int SO = 0x0E;
    private static final int SI = 0x0F;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Set<Integer> PTOCA_TEXT_FUNCTIONS = new HashSet<>(Arrays.asList(
        0xC3, // Transparent Data
        0xC5, // Set Coded Font Local (may include identifiers; tolerated)
        0xD3, // Text data variants by implementation
        0xD8, // Text data variants by implementation
        0xDA, // TRN
        0xDB, // AMI/GOCA integration text variants
        0xDE  // Text data variants
    ));

    private AfpTextDecoders() {
    }

    static DecodeResult decodeTextFragments(AfpStructuredField field, AfpCodePageProfile profile) {
        if (isPtx(field.sfIdHex())) {
            return decodePtx(field.payload(), profile);
        }
        if (isTrn(field.sfIdHex())) {
            return decodeRawTextPayload(field.payload(), 0, profile);
        }
        return new DecodeResult(List.of(), 0, List.of());
    }

    static List<String> decodeTextBytes(byte[] payload, AfpCodePageProfile profile) {
        return decodeRawTextPayload(payload, 0, profile).fragments();
    }

    static List<String> decodeTextRuns(byte[] payload, AfpCodePageProfile profile) {
        if (payload == null || payload.length == 0) {
            return List.of();
        }
        Charset sbcs = profile == null || profile.sbcsCharset() == null ? LATIN1 : profile.sbcsCharset();
        Charset dbcs = profile == null ? null : profile.dbcsCharset();
        List<String> runs = new ArrayList<>();

        ByteArrayOutputStreamWrapper buffer = new ByteArrayOutputStreamWrapper();
        boolean inDbcs = false;
        for (int i = 0; i < payload.length; i++) {
            int b = payload[i] & 0xFF;
            if (b == SO) {
                flushRun(runs, buffer.take(), inDbcs ? dbcs : sbcs, inDbcs);
                inDbcs = true;
                continue;
            }
            if (b == SI) {
                flushRun(runs, buffer.take(), inDbcs ? dbcs : sbcs, inDbcs);
                inDbcs = false;
                continue;
            }
            buffer.write(payload[i]);
        }
        flushRun(runs, buffer.take(), inDbcs ? dbcs : sbcs, inDbcs);

        List<String> cleaned = new ArrayList<>();
        for (String run : runs) {
            if (run == null) {
                continue;
            }
            String normalized = run.replace('\u0000', ' ').replaceAll("[\\r\\n\\t]+", " ");
            if (!normalized.isBlank()) {
                cleaned.add(normalized);
            }
        }
        return List.copyOf(cleaned);
    }

    static int countPotentialTextRunsInPtxPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return 0;
        }
        int runs = 0;
        int i = 0;
        while (i < payload.length) {
            ControlSequence cs = readControlSequence(payload, i);
            if (cs == null) {
                i++;
                continue;
            }
            if (cs.dataLength > 0 && isTextFunction(cs.functionType)) {
                runs++;
            }
            i += cs.length;
        }
        return runs;
    }

    private static DecodeResult decodePtx(byte[] payload, AfpCodePageProfile profile) {
        List<String> fragments = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int controlSequences = 0;

        int i = 0;
        while (i < payload.length) {
            ControlSequence cs = readControlSequence(payload, i);
            if (cs == null) {
                i++;
                continue;
            }
            controlSequences++;
            if (cs.dataLength > 0 && isTextFunction(cs.functionType)) {
                byte[] data = new byte[cs.dataLength];
                System.arraycopy(payload, cs.dataStart, data, 0, cs.dataLength);
                fragments.addAll(decodeRawTextPayload(data, 0, profile).fragments());
            }
            i += cs.length;
        }
        if (controlSequences == 0 && payload.length > 0) {
            warnings.add("No parseable PTX control sequences detected; using payload fallback decode.");
        }

        if (fragments.isEmpty() && payload.length > 0) {
            // Some AFP producers use PTX variants where function IDs are vendor-specific.
            // Fall back to full-payload decode to avoid losing extractable text completely.
            fragments.addAll(decodeRawTextPayload(payload, 0, profile).fragments());
        }

        return new DecodeResult(List.copyOf(fragments), controlSequences, List.copyOf(warnings));
    }

    private static boolean isTextFunction(int fn) {
        return PTOCA_TEXT_FUNCTIONS.contains(fn);
    }

    private static ControlSequence readControlSequence(byte[] payload, int offset) {
        if (payload == null || offset < 0 || offset >= payload.length) {
            return null;
        }
        if ((payload[offset] & 0xFF) != PTOCA_CS_INTRODUCER || offset + 2 >= payload.length) {
            return null;
        }
        int len1 = payload[offset + 1] & 0xFF;
        int len2 = ((payload[offset + 1] & 0xFF) << 8) | (payload[offset + 2] & 0xFF);
        boolean oneByteValid = len1 >= 3 && (offset + len1) <= payload.length;
        boolean twoByteValid = len2 >= 4 && (offset + len2) <= payload.length;
        boolean preferTwoByte = payload[offset + 1] == 0 || (!oneByteValid && twoByteValid);

        if (preferTwoByte && twoByteValid) {
            return new ControlSequence(len2, payload[offset + 3] & 0xFF, offset + 4, len2 - 4);
        }
        if (oneByteValid) {
            return new ControlSequence(len1, payload[offset + 2] & 0xFF, offset + 3, len1 - 3);
        }
        if (twoByteValid) {
            return new ControlSequence(len2, payload[offset + 3] & 0xFF, offset + 4, len2 - 4);
        }
        return null;
    }

    private static DecodeResult decodeRawTextPayload(byte[] payload, int controlSequences, AfpCodePageProfile profile) {
        if (payload.length == 0) {
            return new DecodeResult(List.of(), controlSequences, List.of());
        }

        String decoded = decodeMixed(payload, profile.sbcsCharset(), profile.dbcsCharset());
        String latin1 = decode(payload, LATIN1); // fallback scoring guardrail for malformed data

        String winner = score(decoded) >= score(latin1) ? decoded : latin1;
        List<String> fragments = collectFragments(winner);
        return new DecodeResult(List.copyOf(fragments), controlSequences, List.of());
    }

    private static String decodeMixed(byte[] payload, Charset sbcs, Charset dbcs) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean inDbcs = false;
        while (i < payload.length) {
            int b = payload[i] & 0xFF;
            if (b == SO) {
                inDbcs = true;
                i++;
                continue;
            }
            if (b == SI) {
                inDbcs = false;
                i++;
                continue;
            }
            if (inDbcs && dbcs != null && i + 1 < payload.length) {
                byte[] pair = new byte[] {payload[i], payload[i + 1]};
                out.append(decode(pair, dbcs));
                i += 2;
                continue;
            }
            out.append(decode(new byte[] {payload[i]}, sbcs));
            i++;
        }
        return out.toString();
    }

    private static void flushRun(List<String> runs, byte[] bytes, Charset charset, boolean dbcsRun) {
        if (bytes.length == 0) {
            return;
        }
        Charset effective = charset == null ? LATIN1 : charset;
        String decoded = decode(bytes, effective);
        String latin1 = decode(bytes, LATIN1);
        if (!dbcsRun && score(latin1) > score(decoded)) {
            decoded = latin1;
        }
        String trimmed = normalizeSpacedGlyphs(decoded.trim());
        if (!trimmed.isEmpty()) {
            runs.add(trimmed);
        }
    }

    private static String decode(byte[] payload, Charset charset) {
        try {
            return new String(payload, charset);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int score(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int printable = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isSpaceChar(c) || ",.-_:/()".indexOf(c) >= 0) {
                printable++;
            }
        }
        return printable * 100 / Math.max(1, text.length());
    }

    private static List<String> collectFragments(String decoded) {
        String normalized = decoded.replace('\u0000', ' ').replaceAll("[\\r\\n\\t]", " ");
        List<String> out = new ArrayList<>();
        for (String part : normalized.split("[\\p{Cntrl}]|\\s{2,}")) {
            String trimmed = normalizeSpacedGlyphs(part.trim());
            if (trimmed.length() >= 2 && score(trimmed) >= 70) {
                out.add(trimmed);
            }
        }
        // stable de-dup
        List<String> dedup = new ArrayList<>();
        for (String value : out) {
            if (dedup.stream().noneMatch(existing -> existing.equalsIgnoreCase(value))) {
                dedup.add(value);
            }
        }
        return dedup;
    }

    private static String normalizeSpacedGlyphs(String value) {
        if (value.matches("(?i)([\\p{L}\\p{N}](\\s+[\\p{L}\\p{N}])+).*")) {
            return value.replaceAll("(?<=\\p{L}|\\p{N})\\s+(?=\\p{L}|\\p{N})", "");
        }
        return value;
    }

    private static boolean isPtx(String sfIdHex) {
        return "D3EE9B".equals(sfIdHex);
    }

    private static boolean isTrn(String sfIdHex) {
        return "D3A09B".equals(sfIdHex) || "D3A090".equals(sfIdHex);
    }

    record DecodeResult(List<String> fragments, int ptxControlSequenceCount, List<String> warnings) {
    }

    private record ControlSequence(int length, int functionType, int dataStart, int dataLength) {
    }

    private static final class ByteArrayOutputStreamWrapper {
        private byte[] data = new byte[64];
        private int size = 0;

        private void write(byte value) {
            if (size >= data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = value;
        }

        private byte[] take() {
            byte[] out = Arrays.copyOf(data, size);
            size = 0;
            return out;
        }
    }
}
