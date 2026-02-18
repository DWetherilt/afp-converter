package com.upland.connect.afp.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AfpInterpreter {
    // Heuristic page markers for initial PoC interpretation.
    private static final Set<String> BEGIN_PAGE_IDS = Set.of("D3A8AF");
    private static final Map<String, String> SF_NAMES = Map.of(
        "D3A8A8", "BDT",
        "D3A9A8", "EDT",
        "D3A8AF", "BPG",
        "D3A9AF", "EPG",
        "D3EE9B", "PTX",
        "D3A09B", "TRN",
        "D3A090", "TRN"
    );
    private final AfpStructuredFieldParser parser = new AfpStructuredFieldParser();
    private final AfplibSemanticPass afplibSemanticPass = new AfplibSemanticPass();
    private final AfpCodePageResolver codePageResolver = new AfpCodePageResolver();

    public AfpInterpretation interpret(Path afpFile, String sourceLabel) throws IOException {
        byte[] bytes = Files.readAllBytes(afpFile);
        return interpret(bytes, sourceLabel);
    }

    AfpInterpretation interpret(byte[] afpBytes, String sourceLabel) {
        AfpParseResult parse = parser.parse(afpBytes);
        int pageCount = countPages(parse.fields());

        List<String> warnings = parse.warnings();
        if (parse.fields().isEmpty()) {
            warnings = new java.util.ArrayList<>(warnings);
            warnings.add("No AFP structured fields detected (0x5A records not found).");
        }

        return new AfpInterpretation(
            sourceLabel,
            afpBytes.length,
            parse.fields().size(),
            pageCount,
            parse.skippedBytes(),
            List.copyOf(warnings),
            parse.fields(),
            decodeSemantics(afpBytes, parse.fields()),
            afpBytes
        );
    }

    private static int countPages(List<AfpStructuredField> fields) {
        int count = 0;
        for (AfpStructuredField field : fields) {
            if (BEGIN_PAGE_IDS.contains(field.sfIdHex())) {
                count++;
            }
        }
        if (count > 0) {
            return count;
        }
        // Fallback for partial/unknown AFP streams: at least one page when content exists.
        return fields.isEmpty() ? 0 : 1;
    }

    private AfpSemantics decodeSemantics(byte[] afpBytes, List<AfpStructuredField> fields) {
        AfplibSemanticPass.Result afplib = afplibSemanticPass.decode(afpBytes);

        int bdt = 0;
        int edt = 0;
        int bpg = 0;
        int epg = 0;
        int textObjects = 0;
        int ptxControlSequences = 0;
        List<String> rawFragments = new ArrayList<>();
        List<String> decodeWarnings = new ArrayList<>();
        AfpCodePageProfile codePage = codePageResolver.resolve(afplib.codePageHints(), afplib.textDataChunks(), decodeWarnings);

        for (AfpStructuredField field : fields) {
            String sfId = field.sfIdHex();
            String name = SF_NAMES.getOrDefault(sfId, "UNKNOWN");
            switch (name) {
                case "BDT" -> bdt++;
                case "EDT" -> edt++;
                case "BPG" -> bpg++;
                case "EPG" -> epg++;
                case "PTX", "TRN" -> textObjects++;
                default -> {
                }
            }

            AfpTextDecoders.DecodeResult decoded = AfpTextDecoders.decodeTextFragments(field, codePage);
            if (!decoded.fragments().isEmpty()) {
                rawFragments.addAll(decoded.fragments());
            }
            ptxControlSequences += decoded.ptxControlSequenceCount();
            decodeWarnings.addAll(decoded.warnings());
        }
        if (afplib.used()) {
            bdt = Math.max(bdt, afplib.beginDocumentCount());
            edt = Math.max(edt, afplib.endDocumentCount());
            bpg = Math.max(bpg, afplib.beginPageCount());
            epg = Math.max(epg, afplib.endPageCount());
            textObjects = Math.max(textObjects, afplib.textObjectCount());
            ptxControlSequences = Math.max(ptxControlSequences, afplib.ptxControlSequenceCount());
        }
        decodeWarnings.addAll(afplib.warnings());
        List<String> afplibFragments = new ArrayList<>();
        for (AfplibSemanticPass.TextChunk chunk : afplib.textDataChunks()) {
            AfpCodePageProfile chunkProfile = codePage;
            if (chunk.codePageHint() != null && chunk.codePageHint() > 0) {
                AfpCodePageProfile resolved = codePageResolver.resolve(
                    Set.of(chunk.codePageHint()),
                    List.of(),
                    decodeWarnings
                );
                chunkProfile = new AfpCodePageProfile(
                    resolved.sbcsCharset() != null ? resolved.sbcsCharset() : codePage.sbcsCharset(),
                    resolved.dbcsCharset() != null ? resolved.dbcsCharset() : codePage.dbcsCharset(),
                    resolved.source()
                );
            }
            afplibFragments.addAll(AfpTextDecoders.decodeTextBytes(chunk.bytes(), chunkProfile));
        }
        List<String> fragments = choosePreferredFragments(rawFragments, afplibFragments);

        return new AfpSemantics(
            bdt,
            edt,
            bpg,
            epg,
            textObjects,
            ptxControlSequences,
            afplib.used(),
            afplib.structuredFieldCount(),
            codePage.sbcsCharset() == null ? "" : codePage.sbcsCharset().name(),
            codePage.dbcsCharset() == null ? "" : codePage.dbcsCharset().name(),
            codePage.source(),
            afplib.codePageHints().stream().sorted().toList(),
            codePage.codePageMappingMatrix(),
            codePage.mixedRunChunkCount(),
            codePage.hintedChunkCount(),
            List.copyOf(decodeWarnings),
            List.copyOf(fragments)
        );
    }

    private static List<String> choosePreferredFragments(List<String> rawFragments, List<String> afplibFragments) {
        int rawScore = fragmentSignalScore(rawFragments);
        int afpLibScore = fragmentSignalScore(afplibFragments);
        if (afpLibScore > rawScore) {
            return afplibFragments;
        }
        return rawFragments;
    }

    private static int fragmentSignalScore(List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return 0;
        }
        int score = 0;
        int count = 0;
        for (String fragment : fragments) {
            if (fragment == null || fragment.isBlank()) {
                continue;
            }
            int letters = 0;
            int digits = 0;
            int symbols = 0;
            String value = fragment.trim();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                } else if (Character.isDigit(c)) {
                    digits++;
                } else if (!Character.isWhitespace(c)) {
                    symbols++;
                }
            }
            int signal = letters + digits;
            score += Math.max(0, signal - symbols);
            count++;
        }
        return score + (count * 3);
    }
}
