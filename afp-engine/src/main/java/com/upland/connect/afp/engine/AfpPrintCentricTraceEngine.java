package com.upland.connect.afp.engine;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

final class AfpPrintCentricTraceEngine {
    private static final int PTOCA_CS_INTRODUCER = 0x2B;
    private static final int PTOCA_FN_TRN = 0xDA;
    private static final int PTOCA_FN_TRN_ALT = 0xDB;
    private static final int PTOCA_FN_AMI = 0xC6;
    private static final int PTOCA_FN_RMI = 0xC8;

    private AfpPrintCentricTraceEngine() {
    }

    static Summary analyze(AfpInterpretation interpretation, Path renderedPdf, int eventLimit) {
        return analyze(interpretation, renderedPdf, eventLimit, 0, 0);
    }

    static Summary analyze(AfpInterpretation interpretation,
                           Path renderedPdf,
                           int eventLimit,
                           int decodableImageSignals,
                           int rawFallbackImageSignals) {
        byte[] raw = interpretation.rawAfpBytes();
        List<AfpStructuredField> fields = interpretation.fields();
        int safeLimit = Math.max(16, eventLimit);
        Map<String, Integer> sfHistogram = new LinkedHashMap<>();
        Map<String, Integer> ptxFnHistogram = new LinkedHashMap<>();
        Map<String, Integer> ptxNormalizedFnHistogram = new LinkedHashMap<>();
        Map<String, Integer> ptxAliasHistogram = new LinkedHashMap<>();
        Map<String, Integer> ptxOperandShapeHistogram = new LinkedHashMap<>();
        Map<String, Integer> byteHistogram = new LinkedHashMap<>();
        Map<String, Integer> pdfOperatorHistogram = new LinkedHashMap<>();
        Map<String, Integer> pdfTextOperandHistogram = new LinkedHashMap<>();
        List<TraceEvent> preview = new ArrayList<>();
        int totalEvents = 0;
        int afpImageSignals = 0;

        if (raw != null) {
            for (byte b : raw) {
                int v = b & 0xFF;
                String key = String.format(Locale.ROOT, "0x%02X", v);
                byteHistogram.merge(key, 1, Integer::sum);
            }
        }

        for (AfpStructuredField field : fields) {
            String sfKey = field.sfIdHex();
            sfHistogram.merge(sfKey, 1, Integer::sum);
            if ("D3A8C9".equals(sfKey)) {
                afpImageSignals++;
            }
            totalEvents++;
            if (preview.size() < safeLimit) {
                preview.add(new TraceEvent(
                    field.offset(),
                    "SF",
                    sfKey,
                    "len=" + field.length() + ",payload=" + field.payloadLength()
                ));
            }

            if (!"D3EE9B".equals(field.sfIdHex()) || field.payloadLength() <= 0) {
                continue;
            }
            byte[] payload = field.payload();
            int length = Math.min(payload.length, field.payloadLength());
            int i = 0;
            while (i < length) {
                PtxCs cs = readPtxControlSequence(payload, i, length);
                if (cs == null) {
                    i++;
                    continue;
                }
                String fnKey = String.format(Locale.ROOT, "0x%02X", cs.functionType);
                ptxFnHistogram.merge(fnKey, 1, Integer::sum);
                int normalizedFn = normalizePtxFunction(cs.functionType);
                String normalizedFnKey = String.format(Locale.ROOT, "0x%02X", normalizedFn);
                ptxNormalizedFnHistogram.merge(normalizedFnKey, 1, Integer::sum);
                if ((cs.functionType & 0xFF) != normalizedFn) {
                    String aliasKey = String.format(Locale.ROOT, "0x%02X->0x%02X", cs.functionType & 0xFF, normalizedFn);
                    ptxAliasHistogram.merge(aliasKey, 1, Integer::sum);
                }
                ptxOperandShapeHistogram.merge(normalizedFnKey + ":len=" + cs.dataLength, 1, Integer::sum);
                totalEvents++;
                if (preview.size() < safeLimit) {
                    preview.add(new TraceEvent(
                        field.offset() + 9L + i,
                        "PTX-CS",
                        fnKey,
                        "normFn=" + normalizedFnKey + ",csLen=" + cs.length + ",dataLen=" + cs.dataLength
                    ));
                }
                i += cs.length;
            }
        }

        PdfScanSummary pdfSummary = scanPdfOperators(renderedPdf, safeLimit, preview, pdfTextOperandHistogram);
        pdfOperatorHistogram.putAll(pdfSummary.operatorHistogram);
        totalEvents += pdfSummary.operatorCount;

        return new Summary(
            raw == null ? 0 : raw.length,
            fields.size(),
            totalEvents,
            safeLimit,
            List.copyOf(preview),
            toSortedPairs(sfHistogram),
            toSortedPairs(ptxFnHistogram),
            toSortedPairs(ptxNormalizedFnHistogram),
            toSortedPairs(ptxAliasHistogram),
            toSortedPairs(ptxOperandShapeHistogram),
            topN(toSortedPairs(byteHistogram), 16),
            toSortedPairs(pdfOperatorHistogram),
            toSortedPairs(pdfTextOperandHistogram),
            crossExam(
                ptxFnHistogram,
                ptxNormalizedFnHistogram,
                ptxAliasHistogram,
                afpImageSignals,
                Math.max(0, decodableImageSignals),
                Math.max(0, rawFallbackImageSignals),
                pdfSummary,
                pdfTextOperandHistogram
            )
        );
    }

    private static PdfScanSummary scanPdfOperators(Path renderedPdf,
                                                   int previewLimit,
                                                   List<TraceEvent> preview,
                                                   Map<String, Integer> pdfTextOperandHistogram) {
        if (renderedPdf == null || !Files.exists(renderedPdf)) {
            return PdfScanSummary.empty("rendered-pdf-missing");
        }
        Map<String, Integer> histogram = new LinkedHashMap<>();
        int count = 0;
        try (PDDocument document = PDDocument.load(renderedPdf.toFile())) {
            int pageIndex = 0;
            for (PDPage page : document.getPages()) {
                PDFStreamParser parser = new PDFStreamParser(page);
                parser.parse();
                List<Object> tokens = parser.getTokens();
                int tokenIndex = 0;
                List<COSBase> pendingOperands = new ArrayList<>();
                for (Object token : tokens) {
                    if (token instanceof Operator op) {
                        String key = op.getName();
                        histogram.merge(key, 1, Integer::sum);
                        if (isTextOperator(key)) {
                            String shape = key + ":" + operandShape(pendingOperands);
                            pdfTextOperandHistogram.merge(shape, 1, Integer::sum);
                        }
                        count++;
                        if (preview.size() < previewLimit) {
                            preview.add(new TraceEvent(
                                -1,
                                "PDF-OP",
                                key,
                                "page=" + pageIndex + ",tokenIndex=" + tokenIndex + ",operands=" + operandPreview(pendingOperands, 3)
                            ));
                        }
                        pendingOperands.clear();
                    } else if (token instanceof COSBase) {
                        pendingOperands.add((COSBase) token);
                    }
                    tokenIndex++;
                }
                pageIndex++;
            }
        } catch (IOException e) {
            return PdfScanSummary.empty("pdf-scan-failed:" + e.getClass().getSimpleName());
        }
        return new PdfScanSummary(count, histogram, "");
    }

    private static CrossExam crossExam(Map<String, Integer> ptxFnHistogram,
                                       Map<String, Integer> ptxNormalizedFnHistogram,
                                       Map<String, Integer> ptxAliasHistogram,
                                       int afpImageSignals,
                                       int decodableImageSignals,
                                       int rawFallbackImageSignals,
                                       PdfScanSummary pdfSummary,
                                       Map<String, Integer> pdfTextOperandHistogram) {
        int afpTextSignals = ptxFnHistogram.values().stream().mapToInt(Integer::intValue).sum();
        int pdfTextOps = sumPdfOps(pdfSummary.operatorHistogram, "Tj", "TJ", "'", "\"");
        int pdfImageOps = sumPdfOps(pdfSummary.operatorHistogram, "Do");
        List<String> inferences = new ArrayList<>();
        if (!pdfSummary.warning.isBlank()) {
            inferences.add(pdfSummary.warning);
        }
        if (!ptxAliasHistogram.isEmpty()) {
            inferences.add("ptoca-short-form-aliases-observed");
            List<KeyCount> aliasCounts = toSortedPairs(ptxAliasHistogram);
            inferences.add("ptoca-dominant-alias=" + aliasCounts.getFirst().key());
        }
        if (!pdfTextOperandHistogram.isEmpty()) {
            inferences.add("pdf-text-operand-shapes-captured");
        }
        if (afpTextSignals > 0 && pdfTextOps == 0) {
            inferences.add("afp-text-present-but-no-pdf-text-operators");
        }
        if (afpImageSignals > 0 && decodableImageSignals == 0) {
            inferences.add("afp-image-signals-observed-but-no-decodable-image-payloads");
        }
        if (rawFallbackImageSignals > 0) {
            inferences.add("afp-image-raw-fallback-candidates-observed");
        }
        if (decodableImageSignals > 0 && pdfImageOps == 0) {
            inferences.add("afp-image-signals-present-but-no-pdf-image-operators");
        }
        if (afpTextSignals > 0 && pdfTextOps > 0) {
            inferences.add("text-signal-correlation-observed");
        }
        if (decodableImageSignals > 0 && pdfImageOps > 0) {
            inferences.add("image-signal-correlation-observed");
        }
        return new CrossExam(
            afpTextSignals,
            pdfTextOps,
            afpImageSignals,
            pdfImageOps,
            List.copyOf(inferences)
        );
    }

    private static int sumPdfOps(Map<String, Integer> histogram, String... ops) {
        int total = 0;
        for (String op : ops) {
            total += histogram.getOrDefault(op, 0);
        }
        return total;
    }

    private static boolean isTextOperator(String op) {
        return "Tj".equals(op) || "TJ".equals(op) || "'".equals(op) || "\"".equals(op) || "Td".equals(op) || "Tm".equals(op);
    }

    private static String operandShape(List<COSBase> operands) {
        if (operands == null || operands.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (COSBase operand : operands) {
            parts.add(operandType(operand));
        }
        return String.join("+", parts);
    }

    private static String operandType(COSBase operand) {
        if (operand instanceof COSString) {
            return "str";
        }
        if (operand instanceof COSNumber) {
            return "num";
        }
        if (operand instanceof COSName) {
            return "name";
        }
        return operand == null ? "null" : operand.getClass().getSimpleName();
    }

    private static String operandPreview(List<COSBase> operands, int max) {
        if (operands == null || operands.isEmpty()) {
            return "[]";
        }
        int limit = Math.min(Math.max(1, max), operands.size());
        List<String> preview = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            COSBase operand = operands.get(i);
            if (operand instanceof COSString s) {
                preview.add("str(" + s.getString().length() + ")");
            } else if (operand instanceof COSNumber n) {
                preview.add("num(" + n.floatValue() + ")");
            } else if (operand instanceof COSName name) {
                preview.add("name(" + name.getName() + ")");
            } else {
                preview.add(operandType(operand));
            }
        }
        if (operands.size() > limit) {
            preview.add("...");
        }
        return "[" + String.join(",", preview) + "]";
    }

    private static int normalizePtxFunction(int fn) {
        int normalized = fn & 0xFF;
        if (normalized <= 0x3F) {
            normalized |= 0xC0;
        }
        if (normalized == PTOCA_FN_TRN_ALT) {
            return PTOCA_FN_TRN;
        }
        return normalized;
    }

    private static PtxCs readPtxControlSequence(byte[] payload, int offset, int length) {
        if (payload == null || offset < 0 || offset >= length || offset + 2 >= length) {
            return null;
        }
        if ((payload[offset] & 0xFF) != PTOCA_CS_INTRODUCER) {
            return null;
        }
        int len1 = payload[offset + 1] & 0xFF;
        int len2 = ((payload[offset + 1] & 0xFF) << 8) | (payload[offset + 2] & 0xFF);
        boolean oneByteValid = len1 >= 3 && (offset + len1) <= length;
        boolean twoByteValid = len2 >= 4 && (offset + len2) <= length;
        boolean preferTwoByte = payload[offset + 1] == 0 || (!oneByteValid && twoByteValid);

        if (preferTwoByte && twoByteValid) {
            return new PtxCs(len2, payload[offset + 3] & 0xFF, len2 - 4);
        }
        if (oneByteValid) {
            return new PtxCs(len1, payload[offset + 2] & 0xFF, len1 - 3);
        }
        if (twoByteValid) {
            return new PtxCs(len2, payload[offset + 3] & 0xFF, len2 - 4);
        }
        return null;
    }

    private static List<KeyCount> toSortedPairs(Map<String, Integer> histogram) {
        List<KeyCount> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : histogram.entrySet()) {
            out.add(new KeyCount(entry.getKey(), entry.getValue()));
        }
        out.sort((a, b) -> Integer.compare(b.count, a.count));
        return out;
    }

    private static List<KeyCount> topN(List<KeyCount> values, int n) {
        int limit = Math.min(Math.max(0, n), values.size());
        return List.copyOf(values.subList(0, limit));
    }

    record Summary(int inputBytes,
                   int structuredFieldCount,
                   int totalEvents,
                   int previewLimit,
                   List<TraceEvent> eventsPreview,
                   List<KeyCount> structuredFieldHistogram,
                   List<KeyCount> ptxFunctionHistogram,
                   List<KeyCount> ptxNormalizedFunctionHistogram,
                   List<KeyCount> ptxAliasHistogram,
                   List<KeyCount> ptxOperandShapeHistogram,
                   List<KeyCount> byteHistogramTop,
                   List<KeyCount> pdfOperatorHistogram,
                   List<KeyCount> pdfTextOperandHistogram,
                   CrossExam crossExam) {
    }

    record TraceEvent(long offset, String type, String token, String detail) {
    }

    record KeyCount(String key, int count) {
    }

    record CrossExam(int afpTextSignalCount,
                     int pdfTextOperatorCount,
                     int afpImageSignalCount,
                     int pdfImageOperatorCount,
                     List<String> inferenceHints) {
    }

    private record PtxCs(int length, int functionType, int dataLength) {
    }

    private record PdfScanSummary(int operatorCount, Map<String, Integer> operatorHistogram, String warning) {
        private static PdfScanSummary empty(String warning) {
            return new PdfScanSummary(0, Map.of(), warning);
        }
    }
}
