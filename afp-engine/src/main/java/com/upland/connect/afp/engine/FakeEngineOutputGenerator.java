package com.upland.connect.afp.engine;

import com.upland.connect.afp.api.ConversionOptions;
import com.upland.connect.afp.api.DiagnosticsPolicy;
import com.upland.connect.afp.api.RenderPolicy;
import com.upland.connect.afp.api.ResourceContext;
import org.afplib.afplib.CFI;
import org.afplib.afplib.CFIRG;
import org.afplib.afplib.SCFL;
import org.afplib.base.Triplet;
import org.afplib.io.AfpInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

public final class FakeEngineOutputGenerator {
    private static final Map<String, String> SF_NAMES = Map.ofEntries(
        Map.entry("D3A8A8", "BDT"),
        Map.entry("D3A9A8", "EDT"),
        Map.entry("D3A8AF", "BPG"),
        Map.entry("D3A9AF", "EPG"),
        Map.entry("D3A89B", "BPT"),
        Map.entry("D3A99B", "EPT"),
        Map.entry("D3A8C9", "BIM"),
        Map.entry("D3A9C9", "EIM"),
        Map.entry("D3A892", "BOC"),
        Map.entry("D3A992", "EOC"),
        Map.entry("D3EE92", "OBD"),
        Map.entry("D3EE9B", "PTX"),
        Map.entry("D3A09B", "TRN"),
        Map.entry("D3A090", "TRN")
    );
    private final PdfWriter pdfWriter;
    private final AfpHtmlRenderer htmlRenderer;
    private final AfpNativePdfRenderer nativePdfRenderer;

    public FakeEngineOutputGenerator(PdfWriter pdfWriter) {
        this.pdfWriter = pdfWriter;
        this.htmlRenderer = new AfpHtmlRenderer();
        this.nativePdfRenderer = new AfpNativePdfRenderer();
    }

    public EngineOutputs writeOutputs(Path workspace, String sourceLabel) throws IOException {
        AfpInterpretation interpretation = new AfpInterpretation(
            sourceLabel,
            0,
            0,
            1,
            0,
            java.util.List.of(),
            java.util.List.of(),
            new AfpSemantics(0, 0, 1, 0, 0, 0, false, 0, "Cp500", "", "fallback-default", java.util.List.of(), java.util.List.of(), 0, 0, java.util.List.of(), java.util.List.of()),
            new byte[0]
        );
        return writeOutputs(workspace, interpretation, "default");
    }

    public EngineOutputs writeOutputs(Path workspace, AfpInterpretation interpretation) throws IOException {
        return writeOutputs(workspace, interpretation, "default", null, null);
    }

    public EngineOutputs writeOutputs(Path workspace, AfpInterpretation interpretation, String metadataMode) throws IOException {
        return writeOutputs(workspace, interpretation, metadataMode, null, null);
    }

    public EngineOutputs writeOutputs(Path workspace,
                                      AfpInterpretation interpretation,
                                      String metadataMode,
                                      ConversionOptions options) throws IOException {
        return writeOutputs(workspace, interpretation, metadataMode, options, null);
    }

    public EngineOutputs writeOutputs(Path workspace,
                                      AfpInterpretation interpretation,
                                      String metadataMode,
                                      ConversionOptions options,
                                      ResourceContext resourceContext) throws IOException {
        Path pdf = workspace.resolve("output.pdf");
        Path meta = workspace.resolve("meta.json");
        Path diag = workspace.resolve("diag.json");
        StyleMode styleMode = StyleMode.resolve(metadataMode);
        RenderPolicy renderPolicy = options == null ? new RenderPolicy("native-fidelity", "strict", "prefer-embedded") : options.renderPolicy();
        DiagnosticsPolicy diagnosticsPolicy = options == null ? new DiagnosticsPolicy("standard") : options.diagnosticsPolicy();
        FontResolutionSummary fontResolution = extractFontResolution(interpretation);
        ImageDecodeSummary imageDecode = extractImageDecodeSummary(interpretation);
        ResourceResolutionSummary resourceResolution = extractResourceResolution(fontResolution, imageDecode);
        List<String> ptxTripletHistogram = extractPtxTripletHistogram(interpretation);
        List<String> ptxRawFunctionHistogram = extractPtxRawFunctionHistogram(interpretation.fields());
        AfpCodePageProfile fallbackProfile = new AfpCodePageProfile(
            resolveCharset(interpretation.semantics().resolvedSbcsCharset(), StandardCharsets.ISO_8859_1),
            resolveCharset(interpretation.semantics().resolvedDbcsCharset(), null),
            "semantic-fallback-diagnostics"
        );
        SemanticFallbackSummary semanticFallback = extractSemanticFallbackSummary(interpretation, fallbackProfile);

        String html = new String(htmlRenderer.render(interpretation), StandardCharsets.UTF_8);
        String pdfRenderMode = "native";
        try {
            renderNativePdf(pdf, interpretation, renderPolicy, resourceContext);
        } catch (Exception e) {
            pdfWriter.writeHtmlPdf(pdf, html);
            pdfRenderMode = "html-fallback";
        }
        AfpPrintCentricTraceEngine.Summary printCentricTrace = AfpPrintCentricTraceEngine.analyze(
            interpretation,
            pdf,
            limitByVerbosity(diagnosticsPolicy.verbosity(), 20, 80, 300),
            imageDecode.decodedDirectCount + imageDecode.decodedBySignatureCount,
            imageDecode.rawFallbackCandidateCount
        );
        Files.writeString(meta,
            "{\n" +
                "  \"schemaVersion\": \"1\",\n" +
                "  \"source\": \"" + safeJson(interpretation.sourceLabel()) + "\",\n" +
                "  \"inputBytes\": " + interpretation.inputBytes() + ",\n" +
                "  \"structuredFieldCount\": " + interpretation.structuredFieldCount() + ",\n" +
                "  \"pageCount\": " + interpretation.pageCount() + ",\n" +
                "  \"skippedBytes\": " + interpretation.skippedBytes() + ",\n" +
                "  \"metadataMode\": \"" + safeJson(styleMode.wireValue) + "\",\n" +
                "  \"pdfRenderMode\": \"" + safeJson(pdfRenderMode) + "\",\n" +
                "  \"renderPolicy\": {\"mode\": \"" + safeJson(renderPolicy.mode()) + "\", \"fidelityLevel\": \"" + safeJson(renderPolicy.fidelityLevel()) + "\", \"resourcePolicy\": \"" + safeJson(renderPolicy.resourcePolicy()) + "\"},\n" +
                "  \"diagnosticsPolicy\": {\"verbosity\": \"" + safeJson(diagnosticsPolicy.verbosity()) + "\"},\n" +
                "  \"textObjectCount\": " + interpretation.semantics().textObjectCount() + ",\n" +
                "  \"textFragmentCount\": " + interpretation.semantics().textFragments().size() + ",\n" +
                "  \"codePageDiagnostics\": {\"mappingMatrix\": " + stringPreviewJson(interpretation.semantics().codePageMappingMatrix(), Integer.MAX_VALUE)
                    + ", \"mixedRunChunkCount\": " + interpretation.semantics().mixedRunChunkCount()
                    + ", \"hintedChunkCount\": " + interpretation.semantics().hintedChunkCount() + "},\n" +
                "  \"fontResolution\": " + fontResolutionJson(fontResolution, false) + ",\n" +
                "  \"imageDecode\": " + imageDecodeJson(imageDecode, false) + ",\n" +
                "  \"resourceResolution\": " + resourceResolutionJson(resourceResolution, false) + ",\n" +
                "  \"printCentricTrace\": " + printCentricTraceJson(printCentricTrace, false) + ",\n" +
                "  \"semanticFallback\": " + semanticFallbackJson(semanticFallback) + ",\n" +
                "  \"afpStructure\": " + afpStructureJson(interpretation, styleMode) + "\n" +
                "}\n",
            StandardCharsets.UTF_8);

        Files.writeString(diag,
            "{\n" +
                "  \"schemaVersion\": \"1\",\n" +
                "  \"engine\": { \"name\": \"inproc-afp-interpreter\", \"version\": \"0.1\", \"build\": \"pdfbox-poc\" },\n" +
                "  \"stats\": {\n" +
                "    \"pageCount\": " + interpretation.pageCount() + ",\n" +
                "    \"substitutedFonts\": " + fontResolution.substitutedFontCount + ",\n" +
                "    \"missingResources\": " + fontResolution.unresolvedUsedLocalFontCount + "\n" +
                "  },\n" +
                "  \"warnings\": " + warningsJson(interpretation.warnings()) + ",\n" +
                "  \"afp\": {\n" +
                "    \"renderMode\": \"" + safeJson(renderPolicy.mode()) + "\",\n" +
                "    \"fidelityLevel\": \"" + safeJson(renderPolicy.fidelityLevel()) + "\",\n" +
                "    \"resourcePolicy\": \"" + safeJson(renderPolicy.resourcePolicy()) + "\",\n" +
                "    \"diagVerbosity\": \"" + safeJson(diagnosticsPolicy.verbosity()) + "\",\n" +
                "    \"structuredFieldCount\": " + interpretation.structuredFieldCount() + ",\n" +
                "    \"skippedBytes\": " + interpretation.skippedBytes() + ",\n" +
                "    \"beginDocumentCount\": " + interpretation.semantics().beginDocumentCount() + ",\n" +
                "    \"endDocumentCount\": " + interpretation.semantics().endDocumentCount() + ",\n" +
                "    \"beginPageCount\": " + interpretation.semantics().beginPageCount() + ",\n" +
                "    \"endPageCount\": " + interpretation.semantics().endPageCount() + ",\n" +
                "    \"textObjectCount\": " + interpretation.semantics().textObjectCount() + ",\n" +
                "    \"ptxControlSequenceCount\": " + interpretation.semantics().ptxControlSequenceCount() + ",\n" +
                "    \"ptxTripletHistogram\": " + stringPreviewJson(ptxTripletHistogram, Integer.MAX_VALUE) + ",\n" +
                "    \"ptxRawFunctionHistogram\": " + stringPreviewJson(ptxRawFunctionHistogram, Integer.MAX_VALUE) + ",\n" +
                "    \"afplibUsed\": " + interpretation.semantics().afplibUsed() + ",\n" +
                "    \"afplibStructuredFieldCount\": " + interpretation.semantics().afplibStructuredFieldCount() + ",\n" +
                "    \"resolvedSbcsCharset\": \"" + safeJson(interpretation.semantics().resolvedSbcsCharset()) + "\",\n" +
                "    \"resolvedDbcsCharset\": \"" + safeJson(interpretation.semantics().resolvedDbcsCharset()) + "\",\n" +
                "    \"codePageResolutionSource\": \"" + safeJson(interpretation.semantics().codePageResolutionSource()) + "\",\n" +
                "    \"codePageHints\": " + intPreviewJson(interpretation.semantics().codePageHints(), 20) + ",\n" +
                "    \"codePageMappingMatrix\": " + stringPreviewJson(interpretation.semantics().codePageMappingMatrix(), Integer.MAX_VALUE) + ",\n" +
                "    \"mixedRunChunkCount\": " + interpretation.semantics().mixedRunChunkCount() + ",\n" +
                "    \"hintedChunkCount\": " + interpretation.semantics().hintedChunkCount() + ",\n" +
                "    \"fontResolution\": " + fontResolutionJson(fontResolution, true) + ",\n" +
                "    \"imageDecode\": " + imageDecodeJson(imageDecode, true) + ",\n" +
                "    \"resourceResolution\": " + resourceResolutionJson(resourceResolution, true) + ",\n" +
                "    \"printCentricTrace\": " + printCentricTraceJson(printCentricTrace, true) + ",\n" +
                "    \"semanticFallback\": " + semanticFallbackJson(semanticFallback) + ",\n" +
                "    \"decodeWarnings\": " + stringPreviewJson(interpretation.semantics().decodeWarnings(), limitByVerbosity(diagnosticsPolicy.verbosity(), 10, 20, 60)) + ",\n" +
                "    \"textFragmentsPreview\": " + stringPreviewJson(interpretation.semantics().textFragments(), limitByVerbosity(diagnosticsPolicy.verbosity(), 5, 10, 40)) + ",\n" +
                "    \"fieldsPreview\": " + fieldsPreviewJson(interpretation.fields(), limitByVerbosity(diagnosticsPolicy.verbosity(), 10, 20, 80)) + "\n" +
                "  }\n" +
            "}\n",
            StandardCharsets.UTF_8);

        return new EngineOutputs(pdf, meta, diag, new EngineStats(interpretation.pageCount(), 0, 0));
    }

    private static String safeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String warningsJson(java.util.List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("{ \"code\": \"AFP_PARSE\", \"message\": \"")
                .append(safeJson(warnings.get(i)))
                .append("\", \"severity\": \"WARN\" }");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String fieldsPreviewJson(java.util.List<AfpStructuredField> fields, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int count = Math.min(limit, fields.size());
        for (int i = 0; i < count; i++) {
            AfpStructuredField field = fields.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("{")
                .append("\"offset\": ").append(field.offset()).append(", ")
                .append("\"length\": ").append(field.length()).append(", ")
                .append("\"sfId\": \"").append(safeJson(field.sfIdHex())).append("\", ")
                .append("\"payloadLength\": ").append(field.payloadLength()).append(", ")
                .append("\"flags\": ").append(field.flags())
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String stringPreviewJson(java.util.List<String> values, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int count = Math.min(limit, values.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(safeJson(values.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String intPreviewJson(java.util.List<Integer> values, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int count = Math.min(limit, values.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    private void renderNativePdf(Path pdf,
                                 AfpInterpretation interpretation,
                                 RenderPolicy renderPolicy,
                                 ResourceContext resourceContext) throws IOException {
        String mode = renderPolicy == null ? "native-fidelity" : renderPolicy.mode();
        if ("styled-semantic".equalsIgnoreCase(mode)) {
            String previous = System.getProperty("afp.render.semanticLayout");
            try {
                System.setProperty("afp.render.semanticLayout", "true");
                nativePdfRenderer.render(pdf, interpretation, resourceContext);
            } finally {
                restoreProperty("afp.render.semanticLayout", previous);
            }
            return;
        }
        if ("debug".equalsIgnoreCase(mode)) {
            String previous = System.getProperty("afp.render.semanticLayout");
            try {
                System.setProperty("afp.render.semanticLayout", "false");
                nativePdfRenderer.render(pdf, interpretation, resourceContext);
            } finally {
                restoreProperty("afp.render.semanticLayout", previous);
            }
            return;
        }
        nativePdfRenderer.render(pdf, interpretation, resourceContext);
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static int limitByVerbosity(String verbosity, int minimal, int standard, int verbose) {
        String value = verbosity == null ? "standard" : verbosity.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "minimal", "low" -> minimal;
            case "verbose", "high", "debug" -> verbose;
            default -> standard;
        };
    }

    private static List<String> extractPtxTripletHistogram(AfpInterpretation interpretation) {
        byte[] afp = interpretation.rawAfpBytes();
        if (afp == null || afp.length == 0) {
            return List.of();
        }
        Map<String, Integer> counts = new java.util.TreeMap<>();
        try (AfpInputStream in = new AfpInputStream(new ByteArrayInputStream(afp))) {
            while (true) {
                Object sf = in.readStructuredField();
                if (sf == null) {
                    break;
                }
                String sfName = sf.getClass().getSimpleName();
                if (!"PTX".equals(sfName)) {
                    continue;
                }
                Object csListObj = sf.getClass().getMethod("getCS").invoke(sf);
                if (!(csListObj instanceof List<?> csList)) {
                    continue;
                }
                for (Object cs : csList) {
                    String tripletName = cs == null ? "null" : cs.getClass().getSimpleName();
                    counts.merge(tripletName, 1, Integer::sum);
                }
            }
        } catch (Exception ignored) {
            return List.of("ptx-triplet-scan-failed");
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.add(entry.getKey() + "=" + entry.getValue());
        }
        return List.copyOf(out);
    }

    private static List<String> extractPtxRawFunctionHistogram(List<AfpStructuredField> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        Map<Integer, Integer> counts = new java.util.TreeMap<>();
        for (AfpStructuredField field : fields) {
            if (!"D3EE9B".equals(field.sfIdHex()) || field.payloadLength() <= 0) {
                continue;
            }
            byte[] payload = field.payload();
            int length = Math.min(payload.length, field.payloadLength());
            int i = 0;
            while (i + 2 < length) {
                int marker = payload[i] & 0xFF;
                if (marker != 0x2B) {
                    i++;
                    continue;
                }
                int len1 = payload[i + 1] & 0xFF;
                int len2 = ((payload[i + 1] & 0xFF) << 8) | (payload[i + 2] & 0xFF);
                boolean oneByteValid = len1 >= 3 && (i + len1) <= length;
                boolean twoByteValid = len2 >= 4 && (i + len2) <= length;
                boolean preferTwoByte = payload[i + 1] == 0 || (!oneByteValid && twoByteValid);
                int csLength;
                int fn;
                if (preferTwoByte && twoByteValid) {
                    csLength = len2;
                    fn = payload[i + 3] & 0xFF;
                } else if (oneByteValid) {
                    csLength = len1;
                    fn = payload[i + 2] & 0xFF;
                } else if (twoByteValid) {
                    csLength = len2;
                    fn = payload[i + 3] & 0xFF;
                } else {
                    i++;
                    continue;
                }
                counts.merge(fn, 1, Integer::sum);
                i += csLength;
            }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            out.add(String.format(Locale.ROOT, "0x%02X=%d", entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    private static FontResolutionSummary extractFontResolution(AfpInterpretation interpretation) {
        byte[] afp = interpretation.rawAfpBytes();
        if (afp.length == 0) {
            return FontResolutionSummary.empty();
        }
        List<ScopedFontDefinition> scopedDefinitions = new ArrayList<>();
        List<FontUsageEvent> usageEvents = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int cfiFieldCount = 0;
        boolean parsedWithAfplib = false;
        int pageIndex = 0;
        int resourceDepth = 0;
        int definitionOrder = 0;
        int textObjectOrdinal = 0;

        try (AfpInputStream in = new AfpInputStream(new ByteArrayInputStream(afp))) {
            parsedWithAfplib = true;
            while (true) {
                Object sf = in.readStructuredField();
                if (sf == null) {
                    break;
                }
                String simpleName = sf.getClass().getSimpleName();
                if ("BPG".equals(simpleName)) {
                    pageIndex++;
                } else if ("BRS".equals(simpleName)) {
                    resourceDepth++;
                } else if ("ERS".equals(simpleName)) {
                    resourceDepth = Math.max(0, resourceDepth - 1);
                }
                if (sf instanceof CFI cfi) {
                    cfiFieldCount++;
                    int idx = 0;
                    for (CFIRG rg : cfi.getFixedLengthRG()) {
                        Integer lid = rg.getSection() == null ? idx : rg.getSection();
                        scopedDefinitions.add(new ScopedFontDefinition(
                            lid,
                            rg.getFCSName(),
                            rg.getCPName(),
                            rg.getSVSize(),
                            pageIndex,
                            resourceDepth,
                            definitionOrder++
                        ));
                        idx++;
                    }
                    continue;
                }
                if ("PTX".equals(simpleName) || "TRN".equals(simpleName)) {
                    textObjectOrdinal++;
                    for (Integer lid : extractScflLocalIds(sf, warnings)) {
                        usageEvents.add(new FontUsageEvent(
                            lid,
                            pageIndex,
                            resourceDepth,
                            "text-object-" + textObjectOrdinal
                        ));
                    }
                }
            }
        } catch (Exception e) {
            warnings.add("AFPLib font scan failed: " + e.getClass().getSimpleName());
        }

        Map<Integer, Integer> usageByLocalId = new HashMap<>();
        for (FontUsageEvent usage : usageEvents) {
            usageByLocalId.merge(usage.localFontId, 1, Integer::sum);
        }
        Map<Integer, List<ScopedFontDefinition>> defsByLocalId = new HashMap<>();
        for (ScopedFontDefinition def : scopedDefinitions) {
            defsByLocalId.computeIfAbsent(def.localFontId, ignored -> new ArrayList<>()).add(def);
        }
        List<FontUsageResolutionEvent> resolvedUsageEvents = new ArrayList<>();
        List<FontUsageResolutionEvent> unresolvedUsageEvents = new ArrayList<>();
        int scopedResolvedUsageCount = 0;
        for (FontUsageEvent usage : usageEvents) {
            ScopedFontDefinition resolved = resolveScopedDefinition(defsByLocalId.get(usage.localFontId), usage);
            if (resolved != null) {
                scopedResolvedUsageCount++;
                resolvedUsageEvents.add(FontUsageResolutionEvent.resolved(usage, resolved));
            } else {
                unresolvedUsageEvents.add(FontUsageResolutionEvent.unresolved(usage));
            }
        }

        List<Integer> localIds = new ArrayList<>();
        localIds.addAll(defsByLocalId.keySet());
        for (Integer lid : usageByLocalId.keySet()) {
            if (!localIds.contains(lid)) {
                localIds.add(lid);
            }
        }
        localIds.sort(Integer::compareTo);

        List<FontResolutionEntry> resolved = new ArrayList<>();
        Set<Integer> unresolvedUsed = new java.util.LinkedHashSet<>();
        for (Integer lid : localIds) {
            int usage = usageByLocalId.getOrDefault(lid, 0);
            ScopedFontDefinition representative = pickRepresentativeDefinition(defsByLocalId.get(lid));
            String fcsName = representative == null ? "" : representative.fcsName;
            String cpName = representative == null ? "" : representative.cpName;
            Integer svSize = representative == null ? null : representative.svSize;
            FontAttributes attrs = resolveFontAttributes(fcsName, cpName, lid);
            String source = representative == null ? "fallback" : "scoped-cfi";
            resolved.add(new FontResolutionEntry(
                lid,
                fcsName,
                cpName,
                svSize,
                attrs.family,
                attrs.weight,
                attrs.style,
                usage,
                source
            ));
        }
        for (FontUsageResolutionEvent unresolvedUsage : unresolvedUsageEvents) {
            unresolvedUsed.add(unresolvedUsage.localFontId);
        }

        int distinctUsed = usageByLocalId.size();
        return new FontResolutionSummary(
            parsedWithAfplib,
            cfiFieldCount,
            scopedDefinitions.size(),
            distinctUsed,
            unresolvedUsed.size(),
            unresolvedUsed.size(),
            usageEvents.size(),
            scopedResolvedUsageCount,
            unresolvedUsageEvents.size(),
            resolved,
            List.copyOf(unresolvedUsed),
            warnings,
            resolvedUsageEvents,
            unresolvedUsageEvents
        );
    }

    private static List<Integer> extractScflLocalIds(Object sf, List<String> warnings) {
        List<Integer> localIds = new ArrayList<>();
        try {
            Object csListObj = sf.getClass().getMethod("getCS").invoke(sf);
            if (csListObj instanceof List<?> csList) {
                for (Object cs : csList) {
                    if (cs instanceof Triplet triplet && triplet instanceof SCFL scfl && scfl.getLID() != null) {
                        localIds.add(scfl.getLID());
                    }
                }
            }
        } catch (Exception ignored) {
            warnings.add("Failed to inspect text control sequences for SCFL usage.");
        }
        return localIds;
    }

    private static ScopedFontDefinition resolveScopedDefinition(List<ScopedFontDefinition> candidates, FontUsageEvent usage) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        ScopedFontDefinition best = null;
        for (ScopedFontDefinition candidate : candidates) {
            if (candidate.resourceDepth > usage.resourceDepth) {
                continue;
            }
            if (candidate.pageIndex > 0 && usage.pageIndex > 0 && candidate.pageIndex != usage.pageIndex) {
                continue;
            }
            if (best == null) {
                best = candidate;
                continue;
            }
            if (candidate.resourceDepth > best.resourceDepth) {
                best = candidate;
                continue;
            }
            if (candidate.resourceDepth == best.resourceDepth && candidate.definitionOrder > best.definitionOrder) {
                best = candidate;
            }
        }
        return best;
    }

    private static ScopedFontDefinition pickRepresentativeDefinition(List<ScopedFontDefinition> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        ScopedFontDefinition best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            ScopedFontDefinition candidate = candidates.get(i);
            if (candidate.resourceDepth > best.resourceDepth) {
                best = candidate;
                continue;
            }
            if (candidate.resourceDepth == best.resourceDepth && candidate.definitionOrder > best.definitionOrder) {
                best = candidate;
            }
        }
        return best;
    }

    private static String fontResolutionJson(FontResolutionSummary summary, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
            .append("\"parsedWithAfplib\": ").append(summary.parsedWithAfplib).append(", ")
            .append("\"cfiFieldCount\": ").append(summary.cfiFieldCount).append(", ")
            .append("\"definedFontCount\": ").append(summary.definedFontCount).append(", ")
            .append("\"distinctUsedLocalFontCount\": ").append(summary.distinctUsedLocalFontCount).append(", ")
            .append("\"unresolvedUsedLocalFontCount\": ").append(summary.unresolvedUsedLocalFontCount).append(", ")
            .append("\"substitutedFontCount\": ").append(summary.substitutedFontCount).append(", ")
            .append("\"scopedUsageCount\": ").append(summary.scopedUsageCount).append(", ")
            .append("\"scopedResolvedUsageCount\": ").append(summary.scopedResolvedUsageCount).append(", ")
            .append("\"scopedUnresolvedUsageCount\": ").append(summary.scopedUnresolvedUsageCount).append(", ")
            .append("\"unresolvedUsedLocalFontIds\": ").append(intPreviewJson(summary.unresolvedUsedLocalFontIds, Integer.MAX_VALUE)).append(", ")
            .append("\"warnings\": ").append(stringPreviewJson(summary.warnings, Integer.MAX_VALUE));
        if (verbose) {
            sb.append(", \"entries\": ").append(fontResolutionEntriesJson(summary.entries))
                .append(", \"resolvedUsageEvents\": ").append(fontUsageResolutionEventsJson(summary.resolvedUsageEvents))
                .append(", \"unresolvedUsageEvents\": ").append(fontUsageResolutionEventsJson(summary.unresolvedUsageEvents));
        } else {
            sb.append(", \"entriesPreview\": ").append(fontResolutionEntriesJson(previewEntries(summary.entries, 12)))
                .append(", \"unresolvedUsageEventsPreview\": ").append(fontUsageResolutionEventsJson(previewUsageEvents(summary.unresolvedUsageEvents, 12)));
        }
        sb.append("}");
        return sb.toString();
    }

    private static List<FontResolutionEntry> previewEntries(List<FontResolutionEntry> entries, int limit) {
        int take = Math.min(limit, entries.size());
        return List.copyOf(entries.subList(0, take));
    }

    private static String fontResolutionEntriesJson(List<FontResolutionEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            FontResolutionEntry entry = entries.get(i);
            sb.append("{")
                .append("\"localFontId\": ").append(entry.localFontId).append(", ")
                .append("\"fcsName\": \"").append(safeJson(entry.fcsName)).append("\", ")
                .append("\"cpName\": \"").append(safeJson(entry.cpName)).append("\", ")
                .append("\"svSize\": ").append(entry.svSize == null ? "null" : entry.svSize).append(", ")
                .append("\"resolvedFamily\": \"").append(safeJson(entry.resolvedFamily)).append("\", ")
                .append("\"resolvedWeight\": \"").append(safeJson(entry.resolvedWeight)).append("\", ")
                .append("\"resolvedStyle\": \"").append(safeJson(entry.resolvedStyle)).append("\", ")
                .append("\"usageCount\": ").append(entry.usageCount).append(", ")
                .append("\"source\": \"").append(safeJson(entry.source)).append("\"")
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<FontUsageResolutionEvent> previewUsageEvents(List<FontUsageResolutionEvent> events, int limit) {
        int take = Math.min(limit, events.size());
        return List.copyOf(events.subList(0, take));
    }

    private static String fontUsageResolutionEventsJson(List<FontUsageResolutionEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            FontUsageResolutionEvent event = events.get(i);
            sb.append("{")
                .append("\"pageIndex\": ").append(event.pageIndex).append(", ")
                .append("\"objectId\": \"").append(safeJson(event.objectId)).append("\", ")
                .append("\"localFontId\": ").append(event.localFontId).append(", ")
                .append("\"resourceDepth\": ").append(event.resourceDepth).append(", ")
                .append("\"status\": \"").append(safeJson(event.status)).append("\", ")
                .append("\"resolvedBy\": \"").append(safeJson(event.resolvedBy)).append("\"")
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static FontAttributes resolveFontAttributes(String fcsName, String cpName, int localFontId) {
        String combined = ((fcsName == null ? "" : fcsName) + " " + (cpName == null ? "" : cpName)).toLowerCase(Locale.ROOT);
        boolean bold = combined.contains("bold") || combined.contains("demi");
        boolean italic = combined.contains("italic") || combined.contains("oblique");
        String family;
        if (combined.contains("cour")) {
            family = "Courier";
        } else if (combined.contains("times") || combined.contains("roman") || combined.contains("serif")) {
            family = "Times";
        } else if (combined.contains("sans") || combined.contains("helv")) {
            family = "Helvetica";
        } else {
            int bucket = Math.floorMod(localFontId, 4);
            family = switch (bucket) {
                case 1 -> "Times";
                case 2 -> "Courier";
                default -> "Helvetica";
            };
            if (bucket == 3) {
                bold = true;
            }
        }
        String weight = bold ? "bold" : "normal";
        String style = italic ? "italic" : "normal";
        return new FontAttributes(family, weight, style);
    }

    private static ImageDecodeSummary extractImageDecodeSummary(AfpInterpretation interpretation) {
        List<byte[]> payloads = new ArrayList<>();
        boolean inImage = false;
        ByteArrayOutputStream imageBytes = null;
        for (AfpStructuredField field : interpretation.fields()) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            switch (kind) {
                case "BIM" -> {
                    inImage = true;
                    imageBytes = new java.io.ByteArrayOutputStream();
                }
                case "EIM" -> {
                    if (inImage && imageBytes != null) {
                        payloads.add(imageBytes.toByteArray());
                    }
                    inImage = false;
                    imageBytes = null;
                }
                default -> {
                    if (inImage && imageBytes != null && field.payloadLength() > 0) {
                        imageBytes.write(field.payload(), 0, field.payloadLength());
                    }
                }
            }
        }
        int direct = 0;
        int signature = 0;
        int undecoded = 0;
        int rawFallbackCandidates = 0;
        int descriptorPayloadCount = 0;
        List<String> signaturesUsed = new ArrayList<>();
        java.util.LinkedHashSet<String> resourceHints = new java.util.LinkedHashSet<>();
        for (byte[] payload : payloads) {
            DecodeResult result = decodeImagePayload(payload);
            if (!result.resourceHints.isEmpty()) {
                resourceHints.addAll(result.resourceHints);
            }
            switch (result.status) {
                case "direct" -> direct++;
                case "signature" -> {
                    signature++;
                    if (!result.signature.isBlank()) {
                        signaturesUsed.add(result.signature);
                    }
                }
                case "raw-candidate" -> rawFallbackCandidates++;
                case "descriptor" -> descriptorPayloadCount++;
                default -> undecoded++;
            }
        }
        double confidence = payloads.isEmpty() ? 1.0 : (direct + signature) / (double) payloads.size();
        List<String> warnings = new ArrayList<>();
        if (!payloads.isEmpty() && (direct + signature) == 0) {
            warnings.add("No image objects could be decoded from embedded payloads.");
        }
        ImageBindingSummary bindingSummary = collectImageBindingSummary(interpretation);
        return new ImageDecodeSummary(
            payloads.size(),
            direct,
            signature,
            rawFallbackCandidates,
            descriptorPayloadCount,
            undecoded,
            confidence,
            signaturesUsed,
            new ArrayList<>(resourceHints),
            warnings,
            bindingSummary
        );
    }

    private static ImageBindingSummary collectImageBindingSummary(AfpInterpretation interpretation) {
        if (interpretation == null || interpretation.fields() == null || interpretation.fields().isEmpty()) {
            return ImageBindingSummary.empty();
        }
        List<PageBindingSummary> pages = new ArrayList<>();
        int pageIndex = -1;
        boolean inImage = false;
        ByteArrayOutputStream imageBytes = null;
        boolean inContainer = false;
        ByteArrayOutputStream containerBytes = null;
        for (AfpStructuredField field : interpretation.fields()) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            switch (kind) {
                case "BPG" -> {
                    pageIndex++;
                    ensurePageBindingSummary(pages, pageIndex);
                }
                case "BIM" -> {
                    ensurePageBindingSummary(pages, pageIndex);
                    inImage = true;
                    imageBytes = new ByteArrayOutputStream();
                }
                case "EIM" -> {
                    if (inImage && imageBytes != null) {
                        PageBindingSummary page = ensurePageBindingSummary(pages, pageIndex);
                        page.bimTokens.addAll(extractUtf16ResourceHints(imageBytes.toByteArray(), 16));
                    }
                    inImage = false;
                    imageBytes = null;
                }
                case "BOC" -> {
                    inContainer = true;
                    containerBytes = new ByteArrayOutputStream();
                }
                case "OBD" -> {
                    if (inContainer && containerBytes != null && field.payloadLength() > 0) {
                        containerBytes.write(field.payload(), 0, field.payloadLength());
                    }
                }
                case "EOC" -> {
                    if (inContainer && containerBytes != null) {
                        byte[] payload = containerBytes.toByteArray();
                        BufferedImage decoded = decodeImagePayloadAsBufferedImage(payload);
                        if (decoded != null) {
                            PageBindingSummary page = ensurePageBindingSummary(pages, pageIndex);
                            page.candidateTokens.addAll(extractUtf16ResourceHints(payload, 16));
                            page.candidateCount++;
                            page.candidatePayloadSizes.add(payload.length);
                            page.candidatePayloadPrefixes.add(hexPrefix(payload, 24));
                        }
                    }
                    inContainer = false;
                    containerBytes = null;
                }
                default -> {
                    if (inImage && imageBytes != null && field.payloadLength() > 0) {
                        imageBytes.write(field.payload(), 0, field.payloadLength());
                    }
                    if (inContainer && containerBytes != null && field.payloadLength() > 0) {
                        containerBytes.write(field.payload(), 0, field.payloadLength());
                    }
                }
            }
        }
        mergeAfplibImageBindingTokens(interpretation.rawAfpBytes(), pages);
        int candidateCount = 0;
        int matchedPageCount = 0;
        for (PageBindingSummary page : pages) {
            page.overlapTokens.addAll(page.bimTokens);
            page.overlapTokens.retainAll(page.candidateTokens);
            candidateCount += page.candidateCount;
            if (!page.overlapTokens.isEmpty()) {
                matchedPageCount++;
            }
        }
        return new ImageBindingSummary(pages, candidateCount, matchedPageCount);
    }

    private static void mergeAfplibImageBindingTokens(byte[] afpBytes, List<PageBindingSummary> pages) {
        if (afpBytes == null || afpBytes.length == 0 || pages == null) {
            return;
        }
        int pageIndex = -1;
        try (AfpInputStream in = new AfpInputStream(new ByteArrayInputStream(afpBytes))) {
            while (true) {
                Object sf = in.readStructuredField();
                if (sf == null) {
                    break;
                }
                String name = sf.getClass().getSimpleName();
                switch (name) {
                    case "BPG" -> {
                        pageIndex++;
                        ensurePageBindingSummary(pages, pageIndex);
                    }
                    case "BIM" -> {
                        PageBindingSummary page = ensurePageBindingSummary(pages, pageIndex);
                        page.bimTokens.addAll(extractStructuredFieldReferenceTokens(sf));
                    }
                    case "BOC", "OBD", "EOC" -> {
                        PageBindingSummary page = ensurePageBindingSummary(pages, pageIndex);
                        page.candidateTokens.addAll(extractStructuredFieldReferenceTokens(sf));
                    }
                    default -> {
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep payload-derived diagnostics when AFPLib parsing is partial.
        }
    }

    private static PageBindingSummary ensurePageBindingSummary(List<PageBindingSummary> pages, int pageIndex) {
        int idx = Math.max(0, pageIndex);
        while (pages.size() <= idx) {
            pages.add(new PageBindingSummary());
        }
        return pages.get(idx);
    }

    private static Set<String> extractStructuredFieldReferenceTokens(Object sf) {
        if (sf == null) {
            return Set.of();
        }
        Set<String> tokens = new HashSet<>();
        for (Method method : sf.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName();
            if (!name.startsWith("get") || "getClass".equals(name)) {
                continue;
            }
            String suffix = name.substring(3);
            if (!isLikelyReferenceGetter(suffix)) {
                continue;
            }
            try {
                Object value = method.invoke(sf);
                addReferenceTokens(tokens, value);
            } catch (Exception ignored) {
                // best-effort probing only
            }
        }
        return tokens;
    }

    private static boolean isLikelyReferenceGetter(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return false;
        }
        return suffix.contains("Name")
            || suffix.endsWith("ID")
            || suffix.endsWith("Id")
            || suffix.contains("RID")
            || suffix.contains("Ref")
            || suffix.contains("Resource")
            || suffix.contains("Obj")
            || suffix.contains("OID");
    }

    private static void addReferenceTokens(Set<String> tokens, Object value) {
        if (tokens == null || value == null) {
            return;
        }
        if (value instanceof String s) {
            for (String part : s.split("[\\s,;:/\\\\|]+")) {
                String token = normalizeHintToken(part);
                if (isReferenceToken(token)) {
                    tokens.add(token);
                }
            }
            return;
        }
        if (value instanceof Number n) {
            String token = normalizeHintToken(String.valueOf(n.longValue()));
            if (isReferenceToken(token)) {
                tokens.add(token);
            }
            return;
        }
        if (value instanceof byte[] bytes) {
            addReferenceTokens(tokens, new String(bytes, StandardCharsets.ISO_8859_1));
            for (String hint : extractUtf16ResourceHints(bytes, 12)) {
                addReferenceTokens(tokens, hint);
            }
            return;
        }
        if (value instanceof Enum<?> e) {
            String token = normalizeHintToken(e.name());
            if (isReferenceToken(token)) {
                tokens.add(token);
            }
        }
    }

    private static boolean isReferenceToken(String token) {
        if (token == null || token.length() < 3) {
            return false;
        }
        if (isLikelyFontToken(token)) {
            return false;
        }
        return true;
    }

    private static boolean isLikelyFontToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return token.contains("arial")
            || token.contains("segoe")
            || token.contains("timesnewroman")
            || token.contains("courier")
            || token.contains("helvetica")
            || token.contains("bold")
            || token.contains("italic")
            || token.contains("regular")
            || token.contains("font");
    }

    private static String normalizeHintToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static BufferedImage decodeImagePayloadAsBufferedImage(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(payload));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static DecodeResult decodeImagePayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new DecodeResult("undecoded", "");
        }
        try {
            BufferedImage direct = ImageIO.read(new ByteArrayInputStream(payload));
            if (direct != null) {
                return new DecodeResult("direct", "");
            }
        } catch (Exception ignored) {
            // continue to signature fallback
        }
        SignatureProbe[] probes = new SignatureProbe[] {
            new SignatureProbe("png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}),
            new SignatureProbe("jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            new SignatureProbe("gif", new byte[] {0x47, 0x49, 0x46, 0x38}),
            new SignatureProbe("bmp", new byte[] {0x42, 0x4D}),
            new SignatureProbe("tiff-le", new byte[] {0x49, 0x49, 0x2A, 0x00}),
            new SignatureProbe("tiff-be", new byte[] {0x4D, 0x4D, 0x00, 0x2A})
        };
        for (SignatureProbe probe : probes) {
            int idx = indexOf(payload, probe.signature);
            if (idx < 0 || idx >= payload.length - 8) {
                continue;
            }
            try {
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(payload, idx, payload.length - idx));
                if (decoded != null) {
                    return new DecodeResult("signature", probe.name);
                }
            } catch (Exception ignored) {
                // continue
            }
        }
        List<String> hints = extractUtf16ResourceHints(payload, 12);
        if (isLikelyRasterPayload(payload)) {
            return new DecodeResult("raw-candidate", "");
        }
        if (!hints.isEmpty()) {
            return new DecodeResult("descriptor", "", hints);
        }
        return new DecodeResult("undecoded", "");
    }

    private static boolean isLikelyRasterPayload(byte[] payload) {
        if (payload == null || payload.length < 64) {
            return false;
        }
        int zeros = 0;
        int printable = 0;
        for (byte b : payload) {
            int v = b & 0xFF;
            if (v == 0) {
                zeros++;
            }
            if (v >= 0x20 && v <= 0x7E) {
                printable++;
            }
        }
        if (zeros >= (payload.length / 5) && printable >= (payload.length / 6)) {
            return false;
        }
        return true;
    }

    private static List<String> extractUtf16ResourceHints(byte[] payload, int limit) {
        if (payload == null || payload.length < 8 || limit <= 0) {
            return List.of();
        }
        java.util.LinkedHashSet<String> hints = new java.util.LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        int max = Math.max(1, limit);
        for (int i = 0; i + 1 < payload.length; i += 2) {
            int hi = payload[i] & 0xFF;
            int lo = payload[i + 1] & 0xFF;
            if (hi == 0 && lo >= 0x20 && lo <= 0x7E) {
                sb.append((char) lo);
                continue;
            }
            if (sb.length() >= 4) {
                String hint = sb.toString().trim().replaceAll("\\s+", " ");
                if (!hint.isEmpty()) {
                    hints.add(hint);
                    if (hints.size() >= max) {
                        break;
                    }
                }
            }
            sb.setLength(0);
        }
        if (hints.size() < max && sb.length() >= 4) {
            String hint = sb.toString().trim().replaceAll("\\s+", " ");
            if (!hint.isEmpty()) {
                hints.add(hint);
            }
        }
        return new ArrayList<>(hints);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] invokeByteArrayGetter(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            if (value instanceof byte[] bytes) {
                return bytes;
            }
        } catch (Exception ignored) {
            // best-effort reflective support
        }
        return null;
    }

    private static String imageDecodeJson(ImageDecodeSummary summary, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
            .append("\"imageObjectCount\": ").append(summary.imageObjectCount).append(", ")
            .append("\"decodedDirectCount\": ").append(summary.decodedDirectCount).append(", ")
            .append("\"decodedBySignatureCount\": ").append(summary.decodedBySignatureCount).append(", ")
            .append("\"rawFallbackCandidateCount\": ").append(summary.rawFallbackCandidateCount).append(", ")
            .append("\"descriptorPayloadCount\": ").append(summary.descriptorPayloadCount).append(", ")
            .append("\"undecodedCount\": ").append(summary.undecodedCount).append(", ")
            .append("\"decodeConfidence\": ").append(String.format(Locale.ROOT, "%.6f", summary.decodeConfidence)).append(", ")
            .append("\"warnings\": ").append(stringPreviewJson(summary.warnings, Integer.MAX_VALUE));
        if (verbose) {
            sb.append(", \"signatureDetections\": ").append(stringPreviewJson(summary.signatureDetections, Integer.MAX_VALUE));
            sb.append(", \"resourceHints\": ").append(stringPreviewJson(summary.resourceHints, Integer.MAX_VALUE));
            sb.append(", \"binding\": ").append(imageBindingSummaryJson(summary.binding, true));
        } else {
            sb.append(", \"signatureDetectionsPreview\": ").append(stringPreviewJson(summary.signatureDetections, 12));
            sb.append(", \"resourceHintsPreview\": ").append(stringPreviewJson(summary.resourceHints, 12));
            sb.append(", \"bindingPreview\": ").append(imageBindingSummaryJson(summary.binding, false));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String imageBindingSummaryJson(ImageBindingSummary summary, boolean verbose) {
        if (summary == null) {
            return "{\"pageCount\": 0, \"candidateCount\": 0, \"matchedPageCount\": 0, \"pages\": []}";
        }
        int pageLimit = verbose ? Integer.MAX_VALUE : 4;
        StringBuilder sb = new StringBuilder();
        sb.append("{")
            .append("\"pageCount\": ").append(summary.pages.size()).append(", ")
            .append("\"candidateCount\": ").append(summary.candidateCount).append(", ")
            .append("\"matchedPageCount\": ").append(summary.matchedPageCount).append(", ")
            .append("\"pages\": ").append(pageBindingSummariesJson(summary.pages, pageLimit))
            .append("}");
        return sb.toString();
    }

    private static String pageBindingSummariesJson(List<PageBindingSummary> pages, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int count = Math.min(Math.max(0, limit), pages == null ? 0 : pages.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            PageBindingSummary page = pages.get(i);
            sb.append("{")
                .append("\"pageIndex\": ").append(i + 1).append(", ")
                .append("\"candidateCount\": ").append(page.candidateCount).append(", ")
                .append("\"bimTokenCount\": ").append(page.bimTokens.size()).append(", ")
                .append("\"candidateTokenCount\": ").append(page.candidateTokens.size()).append(", ")
                .append("\"overlapTokenCount\": ").append(page.overlapTokens.size()).append(", ")
                .append("\"bimTokens\": ").append(stringPreviewJson(new ArrayList<>(page.bimTokens), 16)).append(", ")
                .append("\"candidateTokens\": ").append(stringPreviewJson(new ArrayList<>(page.candidateTokens), 16)).append(", ")
                .append("\"overlapTokens\": ").append(stringPreviewJson(new ArrayList<>(page.overlapTokens), 16)).append(", ")
                .append("\"candidatePayloadSizes\": ").append(intPreviewJson(page.candidatePayloadSizes, 8)).append(", ")
                .append("\"candidatePayloadPrefixes\": ").append(stringPreviewJson(page.candidatePayloadPrefixes, 8))
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String hexPrefix(byte[] payload, int maxBytes) {
        if (payload == null || payload.length == 0 || maxBytes <= 0) {
            return "";
        }
        int limit = Math.min(payload.length, maxBytes);
        StringBuilder sb = new StringBuilder(limit * 2);
        for (int i = 0; i < limit; i++) {
            int v = payload[i] & 0xFF;
            sb.append(Character.forDigit((v >>> 4) & 0x0F, 16));
            sb.append(Character.forDigit(v & 0x0F, 16));
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static ResourceResolutionSummary extractResourceResolution(FontResolutionSummary fontResolution, ImageDecodeSummary imageDecode) {
        List<ResourceResolutionEvent> events = new ArrayList<>();
        int resolved = fontResolution.scopedResolvedUsageCount;
        int missing = fontResolution.scopedUnresolvedUsageCount;
        int substituted = fontResolution.substitutedFontCount;
        for (FontUsageResolutionEvent unresolved : fontResolution.unresolvedUsageEvents) {
            events.add(new ResourceResolutionEvent(
                "font",
                "page=" + unresolved.pageIndex + ",object=" + unresolved.objectId + ",localFontId=" + unresolved.localFontId,
                "missing",
                "no-scoped-cfi-match"
            ));
        }
        if (imageDecode.imageObjectCount > 0) {
            if (imageDecode.decodedDirectCount > 0 || imageDecode.decodedBySignatureCount > 0) {
                resolved += (imageDecode.decodedDirectCount + imageDecode.decodedBySignatureCount);
            }
            if (imageDecode.rawFallbackCandidateCount > 0) {
                substituted += imageDecode.rawFallbackCandidateCount;
                events.add(new ResourceResolutionEvent("image", "embedded-payload", "substituted", "raw-fallback-candidate"));
            }
            if (imageDecode.undecodedCount > 0) {
                missing += imageDecode.undecodedCount;
                events.add(new ResourceResolutionEvent("image", "embedded-payload", "missing", "decode-failed"));
            }
        }
        return new ResourceResolutionSummary(resolved, missing, substituted, events);
    }

    private static String resourceResolutionJson(ResourceResolutionSummary summary, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
            .append("\"resolvedCount\": ").append(summary.resolvedCount).append(", ")
            .append("\"missingCount\": ").append(summary.missingCount).append(", ")
            .append("\"substitutedCount\": ").append(summary.substitutedCount);
        if (verbose) {
            sb.append(", \"events\": ").append(resourceResolutionEventsJson(summary.events));
        } else {
            sb.append(", \"eventsPreview\": ").append(resourceResolutionEventsJson(previewResourceEvents(summary.events, 12)));
        }
        sb.append("}");
        return sb.toString();
    }

    private static List<ResourceResolutionEvent> previewResourceEvents(List<ResourceResolutionEvent> events, int limit) {
        int take = Math.min(limit, events.size());
        return List.copyOf(events.subList(0, take));
    }

    private static String resourceResolutionEventsJson(List<ResourceResolutionEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ResourceResolutionEvent event = events.get(i);
            sb.append("{")
                .append("\"type\": \"").append(safeJson(event.type)).append("\", ")
                .append("\"resourceId\": \"").append(safeJson(event.resourceId)).append("\", ")
                .append("\"status\": \"").append(safeJson(event.status)).append("\", ")
                .append("\"reason\": \"").append(safeJson(event.reason)).append("\"")
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static SemanticFallbackSummary extractSemanticFallbackSummary(AfpInterpretation interpretation,
                                                                          AfpCodePageProfile profile) {
        List<String> semantic = interpretation.semantics().textFragments();
        int semanticCursor = 0;
        int lowSignalFields = 0;
        int appliedFallbackFields = 0;
        for (AfpStructuredField field : interpretation.fields()) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            if (!"PTX".equals(kind) && !"TRN".equals(kind)) {
                continue;
            }
            List<String> decoded = AfpTextDecoders.decodeTextFragments(field, profile).fragments();
            if (!isFallbackCandidateText(decoded)) {
                continue;
            }
            lowSignalFields++;
            int remainingSemantic = Math.max(0, semantic.size() - semanticCursor);
            int remainingLowSignal = Math.max(1, lowSignalFields - appliedFallbackFields);
            int take = remainingLowSignal <= 1
                ? remainingSemantic
                : Math.max(1, (int) Math.ceil((double) remainingSemantic / remainingLowSignal));
            int end = Math.min(semantic.size(), semanticCursor + take);
            if (end > semanticCursor) {
                appliedFallbackFields++;
                semanticCursor = end;
            }
        }
        return new SemanticFallbackSummary(
            "run-level-only",
            lowSignalFields,
            appliedFallbackFields,
            semantic.size(),
            semanticCursor,
            Math.max(0, semantic.size() - semanticCursor)
        );
    }

    private static String semanticFallbackJson(SemanticFallbackSummary summary) {
        return "{"
            + "\"mode\": \"" + safeJson(summary.mode) + "\", "
            + "\"lowSignalFieldCount\": " + summary.lowSignalFieldCount + ", "
            + "\"appliedFallbackFieldCount\": " + summary.appliedFallbackFieldCount + ", "
            + "\"semanticFragmentPoolSize\": " + summary.semanticFragmentPoolSize + ", "
            + "\"usedSemanticFragmentCount\": " + summary.usedSemanticFragmentCount + ", "
            + "\"remainingSemanticFragmentCount\": " + summary.remainingSemanticFragmentCount
            + "}";
    }

    private static String printCentricTraceJson(AfpPrintCentricTraceEngine.Summary summary, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
            .append("\"inputBytes\": ").append(summary.inputBytes()).append(", ")
            .append("\"structuredFieldCount\": ").append(summary.structuredFieldCount()).append(", ")
            .append("\"totalEvents\": ").append(summary.totalEvents()).append(", ")
            .append("\"previewLimit\": ").append(summary.previewLimit()).append(", ")
            .append("\"structuredFieldHistogram\": ").append(keyCountJson(summary.structuredFieldHistogram())).append(", ")
            .append("\"ptxFunctionHistogram\": ").append(keyCountJson(summary.ptxFunctionHistogram())).append(", ")
            .append("\"ptxNormalizedFunctionHistogram\": ").append(keyCountJson(summary.ptxNormalizedFunctionHistogram())).append(", ")
            .append("\"ptxAliasHistogram\": ").append(keyCountJson(summary.ptxAliasHistogram())).append(", ")
            .append("\"ptxOperandShapeHistogram\": ").append(keyCountJson(summary.ptxOperandShapeHistogram())).append(", ")
            .append("\"byteHistogramTop\": ").append(keyCountJson(summary.byteHistogramTop())).append(", ")
            .append("\"pdfOperatorHistogram\": ").append(keyCountJson(summary.pdfOperatorHistogram())).append(", ")
            .append("\"pdfTextOperandHistogram\": ").append(keyCountJson(summary.pdfTextOperandHistogram())).append(", ")
            .append("\"crossExam\": ").append(crossExamJson(summary.crossExam()));
        if (verbose) {
            sb.append(", \"events\": ").append(traceEventsJson(summary.eventsPreview()));
        } else {
            sb.append(", \"eventsPreview\": ").append(traceEventsJson(summary.eventsPreview()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String crossExamJson(AfpPrintCentricTraceEngine.CrossExam crossExam) {
        return "{"
            + "\"afpTextSignalCount\": " + crossExam.afpTextSignalCount() + ", "
            + "\"pdfTextOperatorCount\": " + crossExam.pdfTextOperatorCount() + ", "
            + "\"afpImageSignalCount\": " + crossExam.afpImageSignalCount() + ", "
            + "\"pdfImageOperatorCount\": " + crossExam.pdfImageOperatorCount() + ", "
            + "\"inferenceHints\": " + stringPreviewJson(crossExam.inferenceHints(), Integer.MAX_VALUE)
            + "}";
    }

    private static String keyCountJson(List<AfpPrintCentricTraceEngine.KeyCount> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            AfpPrintCentricTraceEngine.KeyCount value = values.get(i);
            sb.append("{\"key\": \"").append(safeJson(value.key())).append("\", \"count\": ").append(value.count()).append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String traceEventsJson(List<AfpPrintCentricTraceEngine.TraceEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            AfpPrintCentricTraceEngine.TraceEvent event = events.get(i);
            sb.append("{")
                .append("\"offset\": ").append(event.offset()).append(", ")
                .append("\"type\": \"").append(safeJson(event.type())).append("\", ")
                .append("\"token\": \"").append(safeJson(event.token())).append("\", ")
                .append("\"detail\": \"").append(safeJson(event.detail())).append("\"")
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String afpStructureJson(AfpInterpretation interpretation, StyleMode styleMode) {
        AfpCodePageProfile profile = new AfpCodePageProfile(
            resolveCharset(interpretation.semantics().resolvedSbcsCharset(), StandardCharsets.ISO_8859_1),
            resolveCharset(interpretation.semantics().resolvedDbcsCharset(), null),
            "metadata-structure"
        );

        List<DocumentNode> documents = new ArrayList<>();
        List<TextElementNode> unplacedTextElements = new ArrayList<>();
        List<TextElementNode> allTextElements = new ArrayList<>();
        List<StructuredFieldNode> structuredFields = new ArrayList<>();
        List<String> semanticFragments = interpretation.semantics().textFragments();
        int semanticCursor = 0;
        Map<Long, List<String>> decodedTextByOffset = new HashMap<>();
        int remainingLowSignalTextFieldCount = 0;
        DocumentNode currentDocument = null;
        PageNode currentPage = null;
        int implicitDocumentCount = 0;
        int globalReadingOrder = 0;
        boolean inImageObject = false;

        for (AfpStructuredField field : interpretation.fields()) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            if ("PTX".equals(kind) || "TRN".equals(kind)) {
                List<String> decoded = AfpTextDecoders.decodeTextFragments(field, profile).fragments();
                decodedTextByOffset.put(field.offset(), decoded);
                if (isFallbackCandidateText(decoded)) {
                    remainingLowSignalTextFieldCount++;
                }
            }
        }

        for (AfpStructuredField field : interpretation.fields()) {
            String kind = SF_NAMES.getOrDefault(field.sfIdHex(), "UNKNOWN");
            List<String> fragments = List.of();
            String textObjectId = "";
            if ("PTX".equals(kind) || "TRN".equals(kind)) {
                fragments = decodedTextByOffset.getOrDefault(field.offset(), List.of());
                if (isFallbackCandidateText(fragments)) {
                    int remainingSemantic = Math.max(0, semanticFragments.size() - semanticCursor);
                    int fallbackTake = remainingLowSignalTextFieldCount <= 1
                        ? remainingSemantic
                        : Math.max(1, (int) Math.ceil((double) remainingSemantic / remainingLowSignalTextFieldCount));
                    List<String> fallback = takeSemanticFallback(semanticFragments, semanticCursor, fallbackTake);
                    semanticCursor += fallback.size();
                    if (!fallback.isEmpty()) {
                        fragments = fallback;
                    }
                    remainingLowSignalTextFieldCount = Math.max(0, remainingLowSignalTextFieldCount - 1);
                }
                globalReadingOrder++;
                TextElementNode textElement = new TextElementNode(
                    field.offset(),
                    field.length(),
                    field.sfIdHex(),
                    kind,
                    fragments,
                    currentDocument == null ? "" : currentDocument.id,
                    currentPage == null ? "" : currentPage.id,
                    globalReadingOrder,
                    currentPage == null ? -1 : currentPage.nextReadingOrder()
                );
                allTextElements.add(textElement);
                textObjectId = textElement.id;
                if (currentPage != null) {
                    currentPage.textElements.add(textElement);
                } else {
                    unplacedTextElements.add(textElement);
                }
            }

            String role = fieldRole(kind, inImageObject);
            StructuredFieldNode fieldNode = new StructuredFieldNode(
                field.offset(),
                field.length(),
                field.sfIdHex(),
                kind,
                field.flags(),
                field.payloadLength(),
                currentDocument == null ? "" : currentDocument.id,
                currentPage == null ? "" : currentPage.id,
                role,
                String.join(" ", fragments),
                fragments,
                textObjectId
            );
            structuredFields.add(fieldNode);
            if (currentPage != null) {
                currentPage.structuredFields.add(fieldNode);
            }

            switch (kind) {
                case "BDT" -> {
                    currentDocument = new DocumentNode(documents.size() + 1, false, field.offset());
                    documents.add(currentDocument);
                    currentPage = null;
                }
                case "EDT" -> {
                    if (currentDocument != null) {
                        currentDocument.endOffset = field.offset();
                    }
                    currentDocument = null;
                    currentPage = null;
                }
                case "BPG" -> {
                    if (currentDocument == null) {
                        currentDocument = new DocumentNode(documents.size() + 1, true, field.offset());
                        documents.add(currentDocument);
                        implicitDocumentCount++;
                    }
                    currentPage = new PageNode(currentDocument.pages.size() + 1, field.offset());
                    currentDocument.pages.add(currentPage);
                }
                case "EPG" -> {
                    if (currentPage != null) {
                        currentPage.endOffset = field.offset();
                    }
                    currentPage = null;
                }
                default -> {
                }
            }
            if ("BIM".equals(kind)) {
                inImageObject = true;
            } else if ("EIM".equals(kind)) {
                inImageObject = false;
            }
        }

        int pageCount = 0;
        int textElementCount = unplacedTextElements.size();
        List<String> documentIds = new ArrayList<>();
        List<String> pageIds = new ArrayList<>();
        for (DocumentNode document : documents) {
            documentIds.add(document.id);
            pageCount += document.pages.size();
            for (PageNode page : document.pages) {
                pageIds.add(page.id);
                textElementCount += page.textElements.size();
            }
        }
        List<String> textElementIds = new ArrayList<>();
        for (TextElementNode textElement : allTextElements) {
            textElementIds.add(textElement.id);
        }
        List<String> unplacedTextElementIds = new ArrayList<>();
        for (TextElementNode textElement : unplacedTextElements) {
            unplacedTextElementIds.add(textElement.id);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\": \"olc-1\"")
            .append(", \"styleMode\": \"").append(safeJson(styleMode.wireValue)).append("\"")
            .append(", \"documents\": ").append(documentsJson(documents, styleMode))
            .append(", \"textElements\": ").append(textElementsJson(allTextElements))
            .append(", \"structuredFields\": ").append(structuredFieldsJson(structuredFields))
            .append(", \"dom\": ").append(domJson(interpretation, documents, allTextElements, styleMode))
            .append(", \"extractedText\": ").append(extractedTextJson(interpretation, allTextElements))
            .append(", \"unplacedTextElements\": ").append(textElementsJson(unplacedTextElements))
            .append(", \"lookup\": {")
            .append("\"documentIds\": ").append(stringPreviewJson(documentIds, Integer.MAX_VALUE)).append(", ")
            .append("\"pageIds\": ").append(stringPreviewJson(pageIds, Integer.MAX_VALUE)).append(", ")
            .append("\"textElementIds\": ").append(stringPreviewJson(textElementIds, Integer.MAX_VALUE)).append(", ")
            .append("\"unplacedTextElementIds\": ").append(stringPreviewJson(unplacedTextElementIds, Integer.MAX_VALUE))
            .append("}")
            .append(", \"totals\": {")
            .append("\"documentCount\": ").append(documents.size()).append(", ")
            .append("\"implicitDocumentCount\": ").append(implicitDocumentCount).append(", ")
            .append("\"pageCount\": ").append(pageCount).append(", ")
            .append("\"textElementCount\": ").append(textElementCount)
            .append("}}");
        return sb.toString();
    }

    private static Charset resolveCharset(String name, Charset fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String documentsJson(List<DocumentNode> documents, StyleMode styleMode) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < documents.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            DocumentNode document = documents.get(i);
            int textElementCount = 0;
            for (PageNode page : document.pages) {
                textElementCount += page.textElements.size();
            }
            sb.append("{")
                .append("\"id\": \"").append(safeJson(document.id)).append("\", ")
                .append("\"documentIndex\": ").append(document.documentIndex).append(", ")
                .append("\"implicit\": ").append(document.implicit).append(", ")
                .append("\"beginOffset\": ").append(document.beginOffset).append(", ")
                .append("\"endOffset\": ").append(document.endOffset).append(", ")
                .append("\"pageIds\": ").append(pageIdsJson(document.pages)).append(", ")
                .append("\"textElementIds\": ").append(pageTextElementIdsJson(document.pages)).append(", ")
                .append("\"pageCount\": ").append(document.pages.size()).append(", ")
                .append("\"textElementCount\": ").append(textElementCount).append(", ")
                .append("\"pages\": ").append(pagesJson(document.pages, styleMode))
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String fieldRole(String kind, boolean inImageObject) {
        if ("BDT".equals(kind) || "EDT".equals(kind) || "BPG".equals(kind) || "EPG".equals(kind)) {
            return "structure";
        }
        if ("PTX".equals(kind) || "TRN".equals(kind) || "BPT".equals(kind) || "EPT".equals(kind)) {
            return "text";
        }
        if ("BIM".equals(kind) || "EIM".equals(kind)) {
            return "image";
        }
        if (inImageObject) {
            return "imageSupport";
        }
        return "control";
    }

    private static String pagesJson(List<PageNode> pages, StyleMode styleMode) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            PageNode page = pages.get(i);
            sb.append("{")
                .append("\"id\": \"").append(safeJson(page.id)).append("\", ")
                .append("\"pageIndex\": ").append(page.pageIndex).append(", ")
                .append("\"beginOffset\": ").append(page.beginOffset).append(", ")
                .append("\"endOffset\": ").append(page.endOffset).append(", ")
                .append("\"structuredFieldIds\": ").append(pageStructuredFieldIdsJson(page)).append(", ")
                .append("\"textElementIds\": ").append(textElementIdsJson(page.textElements)).append(", ")
                .append("\"textIndex\": ").append(textIndexJson(page.textElements)).append(", ")
                .append("\"objects\": ").append(pageObjectsJson(page)).append(", ")
                .append("\"regions\": ").append(regionsJson(page, styleMode)).append(", ")
                .append("\"textElementCount\": ").append(page.textElements.size()).append(", ")
                .append("\"textElements\": ").append(textElementsJson(page.textElements))
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String pageIdsJson(List<PageNode> pages) {
        List<String> ids = new ArrayList<>();
        for (PageNode page : pages) {
            ids.add(page.id);
        }
        return stringPreviewJson(ids, Integer.MAX_VALUE);
    }

    private static String pageTextElementIdsJson(List<PageNode> pages) {
        List<String> ids = new ArrayList<>();
        for (PageNode page : pages) {
            for (TextElementNode textElement : page.textElements) {
                ids.add(textElement.id);
            }
        }
        return stringPreviewJson(ids, Integer.MAX_VALUE);
    }

    private static String pageStructuredFieldIdsJson(PageNode page) {
        List<String> ids = new ArrayList<>();
        for (StructuredFieldNode field : page.structuredFields) {
            ids.add(field.id);
        }
        return stringPreviewJson(ids, Integer.MAX_VALUE);
    }

    private static String textElementIdsJson(List<TextElementNode> textElements) {
        List<String> ids = new ArrayList<>();
        for (TextElementNode textElement : textElements) {
            ids.add(textElement.id);
        }
        return stringPreviewJson(ids, Integer.MAX_VALUE);
    }

    private static String textIndexJson(List<TextElementNode> textElements) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < textElements.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            TextElementNode textElement = textElements.get(i);
            sb.append("{")
                .append("\"textElementId\": \"").append(safeJson(textElement.id)).append("\", ")
                .append("\"pageReadingOrder\": ").append(textElement.pageReadingOrder).append(", ")
                .append("\"globalReadingOrder\": ").append(textElement.globalReadingOrder).append(", ")
                .append("\"text\": \"").append(safeJson(textElement.text)).append("\"")
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String regionsJson(PageNode page, StyleMode styleMode) {
        String textRegionId = "region-text-" + page.id;
        List<String> imageFieldIds = new ArrayList<>();
        for (StructuredFieldNode field : page.structuredFields) {
            if ("image".equals(field.role) || "imageSupport".equals(field.role)) {
                imageFieldIds.add(field.id);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        sb.append("{")
            .append("\"id\": \"").append(safeJson(textRegionId)).append("\", ")
            .append("\"type\": \"textRegion\", ")
            .append("\"textElementIds\": ").append(textElementIdsJson(page.textElements)).append(", ")
            .append("\"styleHints\": ").append(regionTextStyleHintsJson(styleMode))
            .append("}");
        if (!imageFieldIds.isEmpty()) {
            sb.append(", {")
                .append("\"id\": \"region-image-").append(safeJson(page.id)).append("\", ")
                .append("\"type\": \"imageRegion\", ")
                .append("\"structuredFieldIds\": ").append(stringPreviewJson(imageFieldIds, Integer.MAX_VALUE)).append(", ")
                .append("\"styleHints\": ").append(regionImageStyleHintsJson(styleMode))
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String structuredFieldsJson(List<StructuredFieldNode> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            StructuredFieldNode field = fields.get(i);
            sb.append("{")
                .append("\"id\": \"").append(safeJson(field.id)).append("\", ")
                .append("\"offset\": ").append(field.offset).append(", ")
                .append("\"length\": ").append(field.length).append(", ")
                .append("\"sfId\": \"").append(safeJson(field.sfId)).append("\", ")
                .append("\"kind\": \"").append(safeJson(field.kind)).append("\", ")
                .append("\"role\": \"").append(safeJson(field.role)).append("\", ")
                .append("\"flags\": ").append(field.flags).append(", ")
                .append("\"payloadLength\": ").append(field.payloadLength).append(", ")
                .append("\"documentId\": \"").append(safeJson(field.documentId)).append("\", ")
                .append("\"pageId\": \"").append(safeJson(field.pageId)).append("\", ")
                .append("\"textObjectId\": \"").append(safeJson(field.textObjectId)).append("\", ")
                .append("\"parsedText\": \"").append(safeJson(field.parsedText)).append("\", ")
                .append("\"fragments\": ").append(stringPreviewJson(field.fragments, Integer.MAX_VALUE))
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String extractedTextJson(AfpInterpretation interpretation, List<TextElementNode> textElements) {
        List<String> full = interpretation.semantics().textFragments();
        StringBuilder sb = new StringBuilder();
        sb.append("{")
            .append("\"fragmentCount\": ").append(full.size()).append(", ")
            .append("\"fragments\": ").append(stringPreviewJson(full, Integer.MAX_VALUE)).append(", ")
            .append("\"joined\": \"").append(safeJson(String.join(" ", full))).append("\", ")
            .append("\"byTextObject\": ").append(textByObjectJson(textElements))
            .append("}");
        return sb.toString();
    }

    private static String textByObjectJson(List<TextElementNode> textElements) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < textElements.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            TextElementNode textElement = textElements.get(i);
            sb.append("{")
                .append("\"textElementId\": \"").append(safeJson(textElement.id)).append("\", ")
                .append("\"text\": \"").append(safeJson(textElement.text)).append("\", ")
                .append("\"fragments\": ").append(stringPreviewJson(textElement.fragments, Integer.MAX_VALUE))
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String pageObjectsJson(PageNode page) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < page.structuredFields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            StructuredFieldNode field = page.structuredFields.get(i);
            String objectType = switch (field.role) {
                case "text" -> "textObject";
                case "image" -> "imageObject";
                case "imageSupport" -> "imageSupportObject";
                case "structure" -> "structureMarker";
                default -> "controlObject";
            };
            sb.append("{")
                .append("\"id\": \"").append(safeJson(field.id)).append("\", ")
                .append("\"objectType\": \"").append(objectType).append("\", ")
                .append("\"kind\": \"").append(safeJson(field.kind)).append("\", ")
                .append("\"offset\": ").append(field.offset).append(", ")
                .append("\"length\": ").append(field.length).append(", ")
                .append("\"textObjectId\": \"").append(safeJson(field.textObjectId)).append("\", ")
                .append("\"parsedText\": \"").append(safeJson(field.parsedText)).append("\"")
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String domJson(AfpInterpretation interpretation,
                                  List<DocumentNode> documents,
                                  List<TextElementNode> textElements,
                                  StyleMode styleMode) {
        AfpDocumentLayout layout = AfpLayoutInterpreter.infer(interpretation);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"rootId\": \"dom-root\", \"nodes\": [");
        sb.append("{\"id\": \"dom-root\", \"type\": \"documentSet\", \"children\": ");
        List<String> docIds = new ArrayList<>();
        for (DocumentNode document : documents) {
            docIds.add(document.id);
        }
        sb.append(stringPreviewJson(docIds, Integer.MAX_VALUE)).append("}");

        for (DocumentNode document : documents) {
            sb.append(", ");
            sb.append("{\"id\": \"").append(safeJson(document.id)).append("\", ")
                .append("\"type\": \"document\", ")
                .append("\"children\": ").append(documentDomChildrenJson(document, layout))
                .append("}");
            for (PageNode page : document.pages) {
                sb.append(", ");
                List<String> pageChildren = new ArrayList<>();
                pageChildren.add("region-text-" + page.id);
                boolean hasImage = false;
                for (StructuredFieldNode field : page.structuredFields) {
                    if ("image".equals(field.role) || "imageSupport".equals(field.role)) {
                        hasImage = true;
                        break;
                    }
                }
                if (hasImage) {
                    pageChildren.add("region-image-" + page.id);
                }
                sb.append("{\"id\": \"").append(safeJson(page.id)).append("\", ")
                    .append("\"type\": \"page\", ")
                    .append("\"children\": ").append(stringPreviewJson(pageChildren, Integer.MAX_VALUE))
                    .append("}");
                sb.append(", ");
                sb.append("{\"id\": \"region-text-").append(safeJson(page.id)).append("\", ")
                    .append("\"type\": \"textRegion\", ")
                    .append("\"children\": ").append(textElementIdsJson(page.textElements))
                    .append("}");
                if (hasImage) {
                    sb.append(", ");
                    List<String> imageFields = new ArrayList<>();
                    for (StructuredFieldNode field : page.structuredFields) {
                        if ("image".equals(field.role) || "imageSupport".equals(field.role)) {
                            imageFields.add(field.id);
                        }
                    }
                    sb.append("{\"id\": \"region-image-").append(safeJson(page.id)).append("\", ")
                        .append("\"type\": \"imageRegion\", ")
                        .append("\"children\": ").append(stringPreviewJson(imageFields, Integer.MAX_VALUE))
                        .append("}");
                }
            }
            if (hasLayoutTable(layout)) {
                sb.append(", ");
                sb.append("{\"id\": \"table-").append(safeJson(document.id)).append("\", ")
                    .append("\"type\": \"tableRegion\", ")
                    .append("\"headers\": [\"Options\", \"What does this option mean?\", \"Key points\"], ")
                    .append("\"rows\": ").append(tableRowsJson(layout.options()))
                    .append("}");
            }
        }
        for (TextElementNode textElement : textElements) {
            sb.append(", ");
            sb.append("{\"id\": \"").append(safeJson(textElement.id)).append("\", ")
                .append("\"type\": \"textObject\", ")
                .append("\"text\": \"").append(safeJson(textElement.text)).append("\", ")
                .append("\"style\": ").append(styleJson(textElement, styleMode))
                .append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    private static String documentDomChildrenJson(DocumentNode document, AfpDocumentLayout layout) {
        List<String> children = new ArrayList<>();
        for (PageNode page : document.pages) {
            children.add(page.id);
        }
        if (hasLayoutTable(layout)) {
            children.add("table-" + document.id);
        }
        return stringPreviewJson(children, Integer.MAX_VALUE);
    }

    private static boolean hasLayoutTable(AfpDocumentLayout layout) {
        for (AfpOptionRow row : layout.options()) {
            if (!"Option".equalsIgnoreCase(row.option())) {
                return true;
            }
        }
        return false;
    }

    private static String tableRowsJson(List<AfpOptionRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            AfpOptionRow row = rows.get(i);
            sb.append("[\"").append(safeJson(row.option())).append("\", ")
                .append("\"").append(safeJson(row.meaning())).append("\", ")
                .append("\"").append(safeJson(row.keyPoints())).append("\"]");
        }
        sb.append(']');
        return sb.toString();
    }

    private static PageNode firstPage(List<DocumentNode> documents) {
        for (DocumentNode document : documents) {
            if (!document.pages.isEmpty()) {
                return document.pages.get(0);
            }
        }
        return null;
    }

    private static String documentIdForPage(List<DocumentNode> documents, PageNode target) {
        for (DocumentNode document : documents) {
            for (PageNode page : document.pages) {
                if (page == target) {
                    return document.id;
                }
            }
        }
        return "";
    }

    private static String regionTextStyleHintsJson(StyleMode styleMode) {
        if (styleMode == StyleMode.STRICT) {
            return "{\"layout\": \"unspecified\", \"fontFamilyHint\": \"unknown\", \"source\": \"strict\"}";
        }
        return "{\"layout\": \"flow\", \"fontFamilyHint\": \"unknown\", \"source\": \"inferred\"}";
    }

    private static String regionImageStyleHintsJson(StyleMode styleMode) {
        if (styleMode == StyleMode.STRICT) {
            return "{\"fit\": \"unspecified\", \"source\": \"strict\"}";
        }
        return "{\"fit\": \"contain\", \"source\": \"inferred\"}";
    }

    private static String styleJson(TextElementNode textElement, StyleMode styleMode) {
        if (styleMode == StyleMode.STRICT) {
            return "{"
                + "\"fontFamilyHint\": \"unknown\", "
                + "\"fontWeightHint\": \"unknown\", "
                + "\"uppercaseRatio\": -1, "
                + "\"digitRatio\": -1, "
                + "\"source\": \"strict\""
                + "}";
        }
        String text = textElement.text == null ? "" : textElement.text;
        int letters = 0;
        int uppercase = 0;
        int digits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    uppercase++;
                }
            }
            if (Character.isDigit(c)) {
                digits++;
            }
        }
        int uppercaseRatio = letters == 0 ? 0 : (uppercase * 100 / letters);
        int digitRatio = text.isEmpty() ? 0 : (digits * 100 / text.length());
        return "{"
            + "\"fontFamilyHint\": \"unknown\", "
            + "\"fontWeightHint\": \"" + (uppercaseRatio > 70 ? "bold-like" : "normal-like") + "\", "
            + "\"uppercaseRatio\": " + uppercaseRatio + ", "
            + "\"digitRatio\": " + digitRatio + ", "
            + "\"source\": \"inferred\""
            + "}";
    }

    private enum StyleMode {
        INFERRED("inferred"),
        STRICT("strict");

        private final String wireValue;

        StyleMode(String wireValue) {
            this.wireValue = wireValue;
        }

        private static StyleMode resolve(String mode) {
            String normalized = mode == null ? "" : mode.toLowerCase(Locale.ROOT).trim();
            return "inferred".equals(normalized) ? INFERRED : STRICT;
        }
    }

    private static boolean isLowSignalText(List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return true;
        }
        String joined = String.join(" ", fragments);
        int letters = 0;
        int digits = 0;
        int symbols = 0;
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
            } else if (Character.isDigit(c)) {
                digits++;
            } else if (!Character.isWhitespace(c)) {
                symbols++;
            }
        }
        int signal = letters + digits;
        return signal < 2 || symbols > signal;
    }

    private static boolean isFallbackCandidateText(List<String> fragments) {
        if (isLowSignalText(fragments)) {
            return true;
        }
        String joined = String.join(" ", fragments).trim();
        if (joined.isEmpty()) {
            return true;
        }
        int nonWhitespace = 0;
        int whitespace = 0;
        int control = 0;
        int nonAscii = 0;
        int maxRun = 1;
        int run = 1;
        char prev = 0;
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (Character.isWhitespace(c)) {
                whitespace++;
                continue;
            }
            nonWhitespace++;
            if (Character.isISOControl(c)) {
                control++;
            }
            if (c > 0x7E) {
                nonAscii++;
            }
            if (c == prev) {
                run++;
                maxRun = Math.max(maxRun, run);
            } else {
                run = 1;
                prev = c;
            }
        }
        boolean denseSingleToken = joined.length() >= 24 && whitespace == 0;
        boolean repetitive = maxRun >= 6;
        boolean controlHeavy = control > 0 && control * 8 >= Math.max(1, nonWhitespace);
        boolean nonAsciiHeavy = nonAscii > 0 && nonAscii * 3 >= Math.max(1, nonWhitespace);
        boolean implausiblyShort = fragments.size() <= 1 && nonWhitespace <= 2;
        return denseSingleToken || repetitive || controlHeavy || nonAsciiHeavy || implausiblyShort;
    }

    private static List<String> takeSemanticFallback(List<String> fragments, int start, int take) {
        if (fragments == null || fragments.isEmpty() || start >= fragments.size() || take <= 0) {
            return List.of();
        }
        int end = Math.min(fragments.size(), start + take);
        return List.copyOf(fragments.subList(start, end));
    }

    private static String textElementsJson(List<TextElementNode> textElements) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < textElements.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            TextElementNode textElement = textElements.get(i);
            sb.append("{")
                .append("\"id\": \"").append(safeJson(textElement.id)).append("\", ")
                .append("\"documentId\": \"").append(safeJson(textElement.documentId)).append("\", ")
                .append("\"pageId\": \"").append(safeJson(textElement.pageId)).append("\", ")
                .append("\"globalReadingOrder\": ").append(textElement.globalReadingOrder).append(", ")
                .append("\"pageReadingOrder\": ").append(textElement.pageReadingOrder).append(", ")
                .append("\"fieldOffset\": ").append(textElement.fieldOffset).append(", ")
                .append("\"fieldLength\": ").append(textElement.fieldLength).append(", ")
                .append("\"sfId\": \"").append(safeJson(textElement.sfId)).append("\", ")
                .append("\"kind\": \"").append(safeJson(textElement.kind)).append("\", ")
                .append("\"text\": \"").append(safeJson(textElement.text)).append("\", ")
                .append("\"fragments\": ").append(stringPreviewJson(textElement.fragments, Integer.MAX_VALUE))
                .append("}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static final class DocumentNode {
        private final String id;
        private final int documentIndex;
        private final boolean implicit;
        private final long beginOffset;
        private long endOffset;
        private final List<PageNode> pages;

        private DocumentNode(int documentIndex, boolean implicit, long beginOffset) {
            this.id = "doc-" + documentIndex;
            this.documentIndex = documentIndex;
            this.implicit = implicit;
            this.beginOffset = beginOffset;
            this.endOffset = -1;
            this.pages = new ArrayList<>();
        }
    }

    private static final class PageNode {
        private final String id;
        private final int pageIndex;
        private final long beginOffset;
        private long endOffset;
        private final List<TextElementNode> textElements;
        private final List<StructuredFieldNode> structuredFields;
        private int readingOrderCounter;

        private PageNode(int pageIndex, long beginOffset) {
            this.id = "p-" + beginOffset + "-" + pageIndex;
            this.pageIndex = pageIndex;
            this.beginOffset = beginOffset;
            this.endOffset = -1;
            this.textElements = new ArrayList<>();
            this.structuredFields = new ArrayList<>();
            this.readingOrderCounter = 0;
        }

        private int nextReadingOrder() {
            readingOrderCounter++;
            return readingOrderCounter;
        }
    }

    private static final class TextElementNode {
        private final String id;
        private final String documentId;
        private final String pageId;
        private final int globalReadingOrder;
        private final int pageReadingOrder;
        private final long fieldOffset;
        private final int fieldLength;
        private final String sfId;
        private final String kind;
        private final String text;
        private final List<String> fragments;

        private TextElementNode(long fieldOffset,
                                int fieldLength,
                                String sfId,
                                String kind,
                                List<String> fragments,
                                String documentId,
                                String pageId,
                                int globalReadingOrder,
                                int pageReadingOrder) {
            this.id = "te-" + fieldOffset + "-" + sfId.toLowerCase();
            this.documentId = documentId;
            this.pageId = pageId;
            this.globalReadingOrder = globalReadingOrder;
            this.pageReadingOrder = pageReadingOrder;
            this.fieldOffset = fieldOffset;
            this.fieldLength = fieldLength;
            this.sfId = sfId;
            this.kind = kind;
            this.text = String.join(" ", fragments);
            this.fragments = List.copyOf(fragments);
        }

        private static TextElementNode synthetic(int syntheticIndex,
                                                 List<String> fragments,
                                                 String documentId,
                                                 String pageId,
                                                 int globalReadingOrder,
                                                 int pageReadingOrder) {
            String syntheticSfId = "SEM";
            return new TextElementNode(
                -syntheticIndex,
                0,
                syntheticSfId,
                "SEMANTIC",
                fragments,
                documentId,
                pageId,
                globalReadingOrder,
                pageReadingOrder
            );
        }
    }

    private static final class FontResolutionSummary {
        private final boolean parsedWithAfplib;
        private final int cfiFieldCount;
        private final int definedFontCount;
        private final int distinctUsedLocalFontCount;
        private final int unresolvedUsedLocalFontCount;
        private final int substitutedFontCount;
        private final int scopedUsageCount;
        private final int scopedResolvedUsageCount;
        private final int scopedUnresolvedUsageCount;
        private final List<FontResolutionEntry> entries;
        private final List<Integer> unresolvedUsedLocalFontIds;
        private final List<String> warnings;
        private final List<FontUsageResolutionEvent> resolvedUsageEvents;
        private final List<FontUsageResolutionEvent> unresolvedUsageEvents;

        private FontResolutionSummary(boolean parsedWithAfplib,
                                      int cfiFieldCount,
                                      int definedFontCount,
                                      int distinctUsedLocalFontCount,
                                      int unresolvedUsedLocalFontCount,
                                      int substitutedFontCount,
                                      int scopedUsageCount,
                                      int scopedResolvedUsageCount,
                                      int scopedUnresolvedUsageCount,
                                      List<FontResolutionEntry> entries,
                                      List<Integer> unresolvedUsedLocalFontIds,
                                      List<String> warnings,
                                      List<FontUsageResolutionEvent> resolvedUsageEvents,
                                      List<FontUsageResolutionEvent> unresolvedUsageEvents) {
            this.parsedWithAfplib = parsedWithAfplib;
            this.cfiFieldCount = cfiFieldCount;
            this.definedFontCount = definedFontCount;
            this.distinctUsedLocalFontCount = distinctUsedLocalFontCount;
            this.unresolvedUsedLocalFontCount = unresolvedUsedLocalFontCount;
            this.substitutedFontCount = substitutedFontCount;
            this.scopedUsageCount = scopedUsageCount;
            this.scopedResolvedUsageCount = scopedResolvedUsageCount;
            this.scopedUnresolvedUsageCount = scopedUnresolvedUsageCount;
            this.entries = List.copyOf(entries);
            this.unresolvedUsedLocalFontIds = List.copyOf(unresolvedUsedLocalFontIds);
            this.warnings = List.copyOf(warnings);
            this.resolvedUsageEvents = List.copyOf(resolvedUsageEvents);
            this.unresolvedUsageEvents = List.copyOf(unresolvedUsageEvents);
        }

        private static FontResolutionSummary empty() {
            return new FontResolutionSummary(false, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private static final class FontResolutionEntry {
        private final int localFontId;
        private final String fcsName;
        private final String cpName;
        private final Integer svSize;
        private final String resolvedFamily;
        private final String resolvedWeight;
        private final String resolvedStyle;
        private final int usageCount;
        private final String source;

        private FontResolutionEntry(int localFontId,
                                    String fcsName,
                                    String cpName,
                                    Integer svSize,
                                    String resolvedFamily,
                                    String resolvedWeight,
                                    String resolvedStyle,
                                    int usageCount,
                                    String source) {
            this.localFontId = localFontId;
            this.fcsName = fcsName == null ? "" : fcsName;
            this.cpName = cpName == null ? "" : cpName;
            this.svSize = svSize;
            this.resolvedFamily = resolvedFamily == null ? "" : resolvedFamily;
            this.resolvedWeight = resolvedWeight == null ? "" : resolvedWeight;
            this.resolvedStyle = resolvedStyle == null ? "" : resolvedStyle;
            this.usageCount = usageCount;
            this.source = source == null ? "" : source;
        }

        private FontResolutionEntry withUsage(int usageCount) {
            return new FontResolutionEntry(
                localFontId,
                fcsName,
                cpName,
                svSize,
                resolvedFamily,
                resolvedWeight,
                resolvedStyle,
                usageCount,
                source
            );
        }
    }

    private static final class FontAttributes {
        private final String family;
        private final String weight;
        private final String style;

        private FontAttributes(String family, String weight, String style) {
            this.family = family;
            this.weight = weight;
            this.style = style;
        }
    }

    private static final class ScopedFontDefinition {
        private final int localFontId;
        private final String fcsName;
        private final String cpName;
        private final Integer svSize;
        private final int pageIndex;
        private final int resourceDepth;
        private final int definitionOrder;

        private ScopedFontDefinition(int localFontId,
                                     String fcsName,
                                     String cpName,
                                     Integer svSize,
                                     int pageIndex,
                                     int resourceDepth,
                                     int definitionOrder) {
            this.localFontId = localFontId;
            this.fcsName = fcsName == null ? "" : fcsName;
            this.cpName = cpName == null ? "" : cpName;
            this.svSize = svSize;
            this.pageIndex = pageIndex;
            this.resourceDepth = Math.max(0, resourceDepth);
            this.definitionOrder = Math.max(0, definitionOrder);
        }
    }

    private static final class FontUsageEvent {
        private final int localFontId;
        private final int pageIndex;
        private final int resourceDepth;
        private final String objectId;

        private FontUsageEvent(int localFontId, int pageIndex, int resourceDepth, String objectId) {
            this.localFontId = localFontId;
            this.pageIndex = Math.max(0, pageIndex);
            this.resourceDepth = Math.max(0, resourceDepth);
            this.objectId = objectId == null ? "" : objectId;
        }
    }

    private static final class FontUsageResolutionEvent {
        private final int pageIndex;
        private final String objectId;
        private final int localFontId;
        private final int resourceDepth;
        private final String status;
        private final String resolvedBy;

        private FontUsageResolutionEvent(int pageIndex,
                                         String objectId,
                                         int localFontId,
                                         int resourceDepth,
                                         String status,
                                         String resolvedBy) {
            this.pageIndex = Math.max(0, pageIndex);
            this.objectId = objectId == null ? "" : objectId;
            this.localFontId = localFontId;
            this.resourceDepth = Math.max(0, resourceDepth);
            this.status = status == null ? "" : status;
            this.resolvedBy = resolvedBy == null ? "" : resolvedBy;
        }

        private static FontUsageResolutionEvent resolved(FontUsageEvent usage, ScopedFontDefinition def) {
            String resolvedBy = "page=" + def.pageIndex + ",depth=" + def.resourceDepth + ",order=" + def.definitionOrder;
            return new FontUsageResolutionEvent(usage.pageIndex, usage.objectId, usage.localFontId, usage.resourceDepth, "resolved", resolvedBy);
        }

        private static FontUsageResolutionEvent unresolved(FontUsageEvent usage) {
            return new FontUsageResolutionEvent(usage.pageIndex, usage.objectId, usage.localFontId, usage.resourceDepth, "missing", "");
        }
    }

    private static final class SignatureProbe {
        private final String name;
        private final byte[] signature;

        private SignatureProbe(String name, byte[] signature) {
            this.name = name == null ? "" : name;
            this.signature = signature == null ? new byte[0] : signature;
        }
    }

    private static final class DecodeResult {
        private final String status;
        private final String signature;
        private final List<String> resourceHints;

        private DecodeResult(String status, String signature) {
            this(status, signature, List.of());
        }

        private DecodeResult(String status, String signature, List<String> resourceHints) {
            this.status = status == null ? "undecoded" : status;
            this.signature = signature == null ? "" : signature;
            this.resourceHints = resourceHints == null ? List.of() : List.copyOf(resourceHints);
        }
    }

    private static final class ImageDecodeSummary {
        private final int imageObjectCount;
        private final int decodedDirectCount;
        private final int decodedBySignatureCount;
        private final int rawFallbackCandidateCount;
        private final int descriptorPayloadCount;
        private final int undecodedCount;
        private final double decodeConfidence;
        private final List<String> signatureDetections;
        private final List<String> resourceHints;
        private final List<String> warnings;
        private final ImageBindingSummary binding;

        private ImageDecodeSummary(int imageObjectCount,
                                   int decodedDirectCount,
                                   int decodedBySignatureCount,
                                   int rawFallbackCandidateCount,
                                   int descriptorPayloadCount,
                                   int undecodedCount,
                                   double decodeConfidence,
                                   List<String> signatureDetections,
                                   List<String> resourceHints,
                                   List<String> warnings,
                                   ImageBindingSummary binding) {
            this.imageObjectCount = imageObjectCount;
            this.decodedDirectCount = decodedDirectCount;
            this.decodedBySignatureCount = decodedBySignatureCount;
            this.rawFallbackCandidateCount = rawFallbackCandidateCount;
            this.descriptorPayloadCount = descriptorPayloadCount;
            this.undecodedCount = undecodedCount;
            this.decodeConfidence = decodeConfidence;
            this.signatureDetections = List.copyOf(signatureDetections);
            this.resourceHints = List.copyOf(resourceHints);
            this.warnings = List.copyOf(warnings);
            this.binding = binding == null ? ImageBindingSummary.empty() : binding;
        }
    }

    private static final class ImageBindingSummary {
        private final List<PageBindingSummary> pages;
        private final int candidateCount;
        private final int matchedPageCount;

        private ImageBindingSummary(List<PageBindingSummary> pages, int candidateCount, int matchedPageCount) {
            this.pages = pages == null ? List.of() : List.copyOf(pages);
            this.candidateCount = Math.max(0, candidateCount);
            this.matchedPageCount = Math.max(0, matchedPageCount);
        }

        private static ImageBindingSummary empty() {
            return new ImageBindingSummary(List.of(), 0, 0);
        }
    }

    private static final class PageBindingSummary {
        private final Set<String> bimTokens = new java.util.LinkedHashSet<>();
        private final Set<String> candidateTokens = new java.util.LinkedHashSet<>();
        private final Set<String> overlapTokens = new java.util.LinkedHashSet<>();
        private final List<Integer> candidatePayloadSizes = new ArrayList<>();
        private final List<String> candidatePayloadPrefixes = new ArrayList<>();
        private int candidateCount;
    }

    private static final class ResourceResolutionSummary {
        private final int resolvedCount;
        private final int missingCount;
        private final int substitutedCount;
        private final List<ResourceResolutionEvent> events;

        private ResourceResolutionSummary(int resolvedCount,
                                          int missingCount,
                                          int substitutedCount,
                                          List<ResourceResolutionEvent> events) {
            this.resolvedCount = resolvedCount;
            this.missingCount = missingCount;
            this.substitutedCount = substitutedCount;
            this.events = List.copyOf(events);
        }
    }

    private static final class ResourceResolutionEvent {
        private final String type;
        private final String resourceId;
        private final String status;
        private final String reason;

        private ResourceResolutionEvent(String type, String resourceId, String status, String reason) {
            this.type = type == null ? "" : type;
            this.resourceId = resourceId == null ? "" : resourceId;
            this.status = status == null ? "" : status;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final class SemanticFallbackSummary {
        private final String mode;
        private final int lowSignalFieldCount;
        private final int appliedFallbackFieldCount;
        private final int semanticFragmentPoolSize;
        private final int usedSemanticFragmentCount;
        private final int remainingSemanticFragmentCount;

        private SemanticFallbackSummary(String mode,
                                        int lowSignalFieldCount,
                                        int appliedFallbackFieldCount,
                                        int semanticFragmentPoolSize,
                                        int usedSemanticFragmentCount,
                                        int remainingSemanticFragmentCount) {
            this.mode = mode == null ? "" : mode;
            this.lowSignalFieldCount = lowSignalFieldCount;
            this.appliedFallbackFieldCount = appliedFallbackFieldCount;
            this.semanticFragmentPoolSize = semanticFragmentPoolSize;
            this.usedSemanticFragmentCount = usedSemanticFragmentCount;
            this.remainingSemanticFragmentCount = remainingSemanticFragmentCount;
        }
    }

    private static final class StructuredFieldNode {
        private final String id;
        private final long offset;
        private final int length;
        private final String sfId;
        private final String kind;
        private final int flags;
        private final int payloadLength;
        private final String documentId;
        private final String pageId;
        private final String role;
        private final String parsedText;
        private final List<String> fragments;
        private final String textObjectId;

        private StructuredFieldNode(long offset,
                                    int length,
                                    String sfId,
                                    String kind,
                                    int flags,
                                    int payloadLength,
                                    String documentId,
                                    String pageId,
                                    String role,
                                    String parsedText,
                                    List<String> fragments,
                                    String textObjectId) {
            this.id = "sf-" + offset + "-" + sfId.toLowerCase();
            this.offset = offset;
            this.length = length;
            this.sfId = sfId;
            this.kind = kind;
            this.flags = flags;
            this.payloadLength = payloadLength;
            this.documentId = documentId;
            this.pageId = pageId;
            this.role = role;
            this.parsedText = parsedText == null ? "" : parsedText;
            this.fragments = List.copyOf(fragments == null ? List.of() : fragments);
            this.textObjectId = textObjectId == null ? "" : textObjectId;
        }
    }
}
