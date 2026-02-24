package solutions.pointzero.symphony.afp.engine;

import org.afplib.afplib.CFI;
import org.afplib.afplib.CFIRG;
import org.afplib.afplib.PTX;
import org.afplib.afplib.SCFL;
import org.afplib.afplib.TRN;
import org.afplib.base.Triplet;
import org.afplib.io.AfpInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AfplibSemanticPass {
    Result decode(byte[] afpBytes) {
        int sfCount = 0;
        int bdt = 0;
        int edt = 0;
        int bpg = 0;
        int epg = 0;
        int textObjects = 0;
        int ptxControlSequences = 0;
        Set<Integer> cpgidHints = new LinkedHashSet<>();
        List<TextChunk> textDataChunks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Integer, Integer> localFontToCodePage = new HashMap<>();

        try (AfpInputStream in = new AfpInputStream(new ByteArrayInputStream(afpBytes))) {
            while (true) {
                Object sf = in.readStructuredField();
                if (sf == null) {
                    break;
                }
                sfCount++;
                String name = sf.getClass().getSimpleName();
                switch (name) {
                    case "BDT" -> bdt++;
                    case "EDT" -> edt++;
                    case "BPG" -> bpg++;
                    case "EPG" -> epg++;
                    case "PTX", "TRN" -> textObjects++;
                    default -> {
                    }
                }
                collectCodePageHints(sf, cpgidHints, localFontToCodePage);
                if (sf instanceof PTX ptx) {
                    textObjects++;
                    Integer currentCpgid = null;
                    for (Triplet cs : ptx.getCS()) {
                        ptxControlSequences++;
                        if (cs instanceof SCFL scfl) {
                            Integer localId = scfl.getLID();
                            if (localId != null) {
                                currentCpgid = localFontToCodePage.get(localId);
                            }
                        }
                        if (cs instanceof TRN trn && trn.getTRNDATA() != null && trn.getTRNDATA().length > 0) {
                            textDataChunks.add(new TextChunk(trn.getTRNDATA(), currentCpgid));
                            if (currentCpgid != null && currentCpgid > 0) {
                                cpgidHints.add(currentCpgid);
                            }
                        }
                    }
                }
            }
            return new Result(
                true,
                sfCount,
                bdt,
                edt,
                bpg,
                epg,
                textObjects,
                ptxControlSequences,
                Set.copyOf(cpgidHints),
                List.copyOf(textDataChunks),
                List.copyOf(warnings)
            );
        } catch (IOException | RuntimeException e) {
            warnings.add("AFPLib parse fallback: " + e.getMessage());
            return new Result(false, 0, 0, 0, 0, 0, 0, 0, Set.of(), List.of(), List.copyOf(warnings));
        }
    }

    private static void collectCodePageHints(Object sf, Set<Integer> output, Map<Integer, Integer> localFontToCodePage) {
        if (sf instanceof CFI cfi) {
            int idx = 0;
            for (CFIRG rg : cfi.getFixedLengthRG()) {
                Integer parsed = parseCodePageFromName(rg.getCPName());
                if (parsed != null && parsed > 0) {
                    output.add(parsed);
                    Integer localId = rg.getSection() != null ? rg.getSection() : idx;
                    localFontToCodePage.put(localId, parsed);
                }
                idx++;
            }
        }
        for (Method method : sf.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName().toLowerCase();
            if (!(name.contains("cpgid") || name.contains("codepage") || name.contains("code_page"))) {
                continue;
            }
            Class<?> type = method.getReturnType();
            try {
                Object value = method.invoke(sf);
                if (value == null) {
                    continue;
                }
                if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
                    int v = ((Number) value).intValue();
                    if (v > 0) {
                        output.add(v);
                    }
                } else if (value instanceof String s) {
                    Integer parsed = parseNumeric(s);
                    if (parsed != null && parsed > 0) {
                        output.add(parsed);
                    }
                }
            } catch (Exception ignored) {
                // best-effort reflection only
            }
        }
    }

    private static Integer parseNumeric(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseCodePageFromName(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() >= 4) {
            String last4 = digits.substring(digits.length() - 4);
            try {
                return Integer.parseInt(last4);
            } catch (NumberFormatException ignored) {
                // continue
            }
        }
        if (digits.length() >= 3) {
            String last3 = digits.substring(digits.length() - 3);
            try {
                return Integer.parseInt(last3);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    record Result(boolean used,
                  int structuredFieldCount,
                  int beginDocumentCount,
                  int endDocumentCount,
                  int beginPageCount,
                  int endPageCount,
                  int textObjectCount,
                  int ptxControlSequenceCount,
                  Set<Integer> codePageHints,
                  List<TextChunk> textDataChunks,
                  List<String> warnings) {
    }

    record TextChunk(byte[] bytes, Integer codePageHint) {
    }
}
