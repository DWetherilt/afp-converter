package com.upland.connect.afp.engine;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class AfpCodePageResolver {
    private static final Charset DEFAULT_SBCS = charsetOrNull("Cp500");
    private static final Charset DEFAULT_DBCS = charsetOrNull("x-IBM939");
    private static final String[] FALLBACK_SBCS_ORDER = new String[] {
        "Cp037", "Cp1047", "Cp500", "Cp1140", "ISO-8859-1"
    };

    private static final Map<Integer, String> SBCS_CPGID_TO_CHARSET = new LinkedHashMap<>();
    private static final Map<Integer, String> DBCS_CPGID_TO_CHARSET = new LinkedHashMap<>();

    static {
        // Common SBCS EBCDIC code pages.
        SBCS_CPGID_TO_CHARSET.put(37, "Cp037");
        SBCS_CPGID_TO_CHARSET.put(273, "Cp273");
        SBCS_CPGID_TO_CHARSET.put(277, "Cp277");
        SBCS_CPGID_TO_CHARSET.put(278, "Cp278");
        SBCS_CPGID_TO_CHARSET.put(280, "Cp280");
        SBCS_CPGID_TO_CHARSET.put(284, "Cp284");
        SBCS_CPGID_TO_CHARSET.put(285, "Cp285");
        SBCS_CPGID_TO_CHARSET.put(297, "Cp297");
        SBCS_CPGID_TO_CHARSET.put(500, "Cp500");
        SBCS_CPGID_TO_CHARSET.put(871, "Cp871");
        SBCS_CPGID_TO_CHARSET.put(1047, "Cp1047");
        SBCS_CPGID_TO_CHARSET.put(1140, "Cp1140");
        SBCS_CPGID_TO_CHARSET.put(1141, "Cp1141");
        SBCS_CPGID_TO_CHARSET.put(1142, "Cp1142");
        SBCS_CPGID_TO_CHARSET.put(1143, "Cp1143");
        SBCS_CPGID_TO_CHARSET.put(1144, "Cp1144");
        SBCS_CPGID_TO_CHARSET.put(1145, "Cp1145");
        SBCS_CPGID_TO_CHARSET.put(1146, "Cp1146");
        SBCS_CPGID_TO_CHARSET.put(1147, "Cp1147");
        SBCS_CPGID_TO_CHARSET.put(1148, "Cp1148");
        SBCS_CPGID_TO_CHARSET.put(1149, "Cp1149");

        // Common DBCS EBCDIC code pages.
        DBCS_CPGID_TO_CHARSET.put(930, "x-IBM930");
        DBCS_CPGID_TO_CHARSET.put(933, "x-IBM933");
        DBCS_CPGID_TO_CHARSET.put(935, "x-IBM935");
        DBCS_CPGID_TO_CHARSET.put(937, "x-IBM937");
        DBCS_CPGID_TO_CHARSET.put(939, "x-IBM939");
    }

    AfpCodePageProfile resolve(Set<Integer> cpgidHints,
                               java.util.List<AfplibSemanticPass.TextChunk> textChunks,
                               java.util.List<String> warnings) {
        Charset sbcs = null;
        Charset dbcs = null;
        java.util.List<String> mappingMatrix = new java.util.ArrayList<>();
        int hintedChunkCount = 0;
        int mixedRunChunkCount = 0;

        for (Integer hint : cpgidHints) {
            if (hint == null || hint <= 0) {
                continue;
            }
            String sbcsName = SBCS_CPGID_TO_CHARSET.get(hint);
            String dbcsName = DBCS_CPGID_TO_CHARSET.get(hint);
            boolean sbcsSupported = sbcsName != null && charsetOrNull(sbcsName) != null;
            boolean dbcsSupported = dbcsName != null && charsetOrNull(dbcsName) != null;
            mappingMatrix.add(
                "cpgid=" + hint
                    + " sbcs=" + (sbcsName == null ? "-" : sbcsName) + "(" + (sbcsSupported ? "supported" : "unmapped") + ")"
                    + " dbcs=" + (dbcsName == null ? "-" : dbcsName) + "(" + (dbcsSupported ? "supported" : "unmapped") + ")"
            );
            if (sbcs == null) {
                sbcs = resolveCharset(sbcsName, hint, warnings);
            }
            if (dbcs == null) {
                dbcs = resolveCharset(dbcsName, hint, warnings);
            }
        }
        for (AfplibSemanticPass.TextChunk chunk : textChunks) {
            if (chunk.codePageHint() != null && chunk.codePageHint() > 0) {
                hintedChunkCount++;
            }
            if (containsShiftMarkers(chunk.bytes())) {
                mixedRunChunkCount++;
            }
        }

        String source;
        if (sbcs != null || dbcs != null) {
            source = "afplib-cpgid";
        } else {
            sbcs = chooseBestFallbackSbcs(textChunks);
            dbcs = DEFAULT_DBCS;
            source = "fallback-heuristic";
        }
        if (mixedRunChunkCount > 0) {
            if (dbcs == null) {
                dbcs = DEFAULT_DBCS;
                source = source + "+mixed-run-dbcs-fallback";
                warnings.add("Detected SO/SI mixed-run text with no DBCS hint; using fallback DBCS " + (dbcs == null ? "none" : dbcs.name()));
            } else {
                source = source + "+mixed-run";
            }
        }

        if (sbcs == null) {
            sbcs = charsetOrNull("ISO-8859-1");
            warnings.add("No usable SBCS charset found; falling back to ISO-8859-1");
        }
        if (mappingMatrix.isEmpty()) {
            mappingMatrix.add("no-cpgid-hints sbcs=" + (sbcs == null ? "-" : sbcs.name()) + " dbcs=" + (dbcs == null ? "-" : dbcs.name()));
        }
        return new AfpCodePageProfile(sbcs, dbcs, source, java.util.List.copyOf(mappingMatrix), mixedRunChunkCount, hintedChunkCount);
    }

    private static Charset chooseBestFallbackSbcs(java.util.List<AfplibSemanticPass.TextChunk> textChunks) {
        Charset best = DEFAULT_SBCS;
        int bestScore = Integer.MIN_VALUE;

        for (String candidateName : FALLBACK_SBCS_ORDER) {
            Charset cs = charsetOrNull(candidateName);
            if (cs == null) {
                continue;
            }
            int score = 0;
            int chunks = 0;
            for (AfplibSemanticPass.TextChunk chunk : textChunks) {
                byte[] bytes = chunk.bytes();
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                String decoded = new String(bytes, cs);
                score += printableScore(decoded);
                chunks++;
                if (chunks >= 200) {
                    break;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = cs;
            }
        }
        return best == null ? DEFAULT_SBCS : best;
    }

    private static int printableScore(String text) {
        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isSpaceChar(c)) {
                score += 3;
            } else if (",.-_:/()%$#&'\"?!".indexOf(c) >= 0) {
                score += 2;
            } else if (Character.isISOControl(c)) {
                score -= 3;
            }
        }
        return score;
    }

    private static Charset resolveCharset(String charsetName, int cpgid, java.util.List<String> warnings) {
        if (charsetName == null) {
            return null;
        }
        Charset charset = charsetOrNull(charsetName);
        if (charset == null) {
            warnings.add("Unsupported charset mapping for CPGID " + cpgid + ": " + charsetName);
        }
        return charset;
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean containsShiftMarkers(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        boolean sawSo = false;
        boolean sawSi = false;
        for (byte value : bytes) {
            int b = value & 0xFF;
            if (b == 0x0E) {
                sawSo = true;
            } else if (b == 0x0F) {
                sawSi = true;
            }
            if (sawSo && sawSi) {
                return true;
            }
        }
        return false;
    }
}
