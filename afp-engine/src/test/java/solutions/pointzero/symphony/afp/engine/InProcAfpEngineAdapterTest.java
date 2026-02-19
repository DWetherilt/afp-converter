package solutions.pointzero.symphony.afp.engine;

import solutions.pointzero.symphony.afp.api.ConversionOptions;
import solutions.pointzero.symphony.afp.api.DiagnosticsPolicy;
import solutions.pointzero.symphony.afp.api.FontPolicy;
import solutions.pointzero.symphony.afp.api.GeometryPolicy;
import solutions.pointzero.symphony.afp.api.Limits;
import solutions.pointzero.symphony.afp.api.MetadataPolicy;
import solutions.pointzero.symphony.afp.api.RenderPolicy;
import solutions.pointzero.symphony.afp.api.ResourceContext;
import solutions.pointzero.symphony.afp.api.TextFallback;
import solutions.pointzero.symphony.afp.api.TextPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcAfpEngineAdapterTest {
    @Test
    void writesExpectedWorkspaceOutputs(@TempDir Path tempDir) throws Exception {
        Path afpFile = tempDir.resolve("input.afp");
        Files.write(afpFile, concat(
            sf("D3A8A8", new byte[0]),
            sf("D3A8AF", new byte[0]),
            sf("D3EE9B", ptxCs((byte) 0xDA, "HELLO AFP".getBytes(StandardCharsets.ISO_8859_1))),
            sf("D3A9AF", new byte[0]),
            sf("D3A9A8", new byte[0])
        ));
        Path workspace = tempDir.resolve("work");
        Files.createDirectories(workspace);

        InProcAfpEngineAdapter adapter = new InProcAfpEngineAdapter("pdfbox", Map.of());
        EngineOutputs outputs = adapter.convertToWorkspace(
            new EngineInputs(
                afpFile,
                "input.afp",
                new ResourceContext(java.util.List.of(), Optional.empty(), false),
                new ConversionOptions(
                    new TextPolicy(TextFallback.SUBSTITUTE, true),
                    new FontPolicy("default"),
                    new GeometryPolicy("default"),
                    new MetadataPolicy("inferred"),
                    true
                ),
                new Limits(10_000, 100, Duration.ofSeconds(5), 1000, 1024 * 1024),
                Optional.empty()
            ),
            workspace
        );

        assertTrue(Files.exists(outputs.pdfPath()), "output.pdf should exist");
        assertTrue(Files.exists(outputs.metaJsonPath()), "meta.json should exist");
        assertTrue(Files.exists(outputs.diagJsonPath()), "diag.json should exist");
        assertTrue(Files.size(outputs.pdfPath()) > 0, "output.pdf should be non-empty");

        String meta = Files.readString(outputs.metaJsonPath());
        String diag = Files.readString(outputs.diagJsonPath());
        assertTrue(meta.contains("\"structuredFieldCount\""), "meta should include interpreted AFP stats");
        assertTrue(meta.contains("\"afpStructure\""), "meta should include AFP hierarchy structure");
        assertTrue(meta.contains("\"schemaVersion\": \"olc-1\""), "afpStructure should include OL Connect schema version");
        assertTrue(meta.contains("\"textElements\""), "meta should include extracted text elements");
        assertTrue(meta.contains("\"structuredFields\""), "meta should include full structured field listing");
        assertTrue(meta.contains("\"dom\""), "meta should include DOM-style object tree");
        assertTrue(meta.contains("\"extractedText\""), "meta should include full extracted text catalog");
        assertTrue(meta.contains("\"objects\""), "meta should include AFP-ordered page object list");
        assertTrue(meta.contains("\"id\": \"te-"), "meta should include stable text element ids");
        assertTrue(meta.contains("\"textIndex\""), "meta should include page-level text index");
        assertTrue(meta.contains("\"globalReadingOrder\""), "meta should include reading order metadata");
        assertTrue(meta.contains("\"parsedText\""), "meta should include parsed text at structured field level");
        assertTrue(meta.contains("HELLO AFP"), "meta should include decoded text fragments");
        assertTrue(meta.contains("\"metadataMode\": \"inferred\""), "meta should expose metadata mode");
        assertTrue(meta.contains("\"pdfRenderMode\": \"native\""), "meta should expose native PDF renderer mode");
        assertTrue(meta.contains("\"renderPolicy\""), "meta should expose render policy");
        assertTrue(meta.contains("\"diagnosticsPolicy\""), "meta should expose diagnostics policy");
        assertTrue(meta.contains("\"codePageDiagnostics\""), "meta should expose code page diagnostics");
        assertTrue(meta.contains("\"semanticFallback\""), "meta should expose semantic fallback diagnostics");
        assertTrue(meta.contains("\"mode\": \"run-level-only\""), "semantic fallback should be run-level-only");
        assertTrue(meta.contains("\"fontResolution\""), "meta should expose font resolution diagnostics");
        assertTrue(meta.contains("\"scopedUsageCount\""), "meta should expose scoped font usage counters");
        assertTrue(meta.contains("\"unresolvedUsageEventsPreview\""), "meta should expose unresolved usage event preview");
        assertTrue(meta.contains("\"imageDecode\""), "meta should expose image decode diagnostics");
        assertTrue(meta.contains("\"resourceResolution\""), "meta should expose resource resolution trace");
        assertTrue(meta.contains("\"source\": \"inferred\""), "inferred mode should include inferred styling source");
        assertTrue(diag.contains("\"fieldsPreview\""), "diag should include structured field preview");
        assertTrue(diag.contains("\"ptxTripletHistogram\""), "diag should include PTX triplet histogram");
        assertTrue(diag.contains("\"ptxRawFunctionHistogram\""), "diag should include raw PTX function histogram");
        assertTrue(diag.contains("\"printCentricTrace\""), "diag should include print-centric trace block");
        assertTrue(diag.contains("\"pdfOperatorHistogram\""), "print-centric trace should include PDF operator histogram");
        assertTrue(diag.contains("\"ptxNormalizedFunctionHistogram\""), "print-centric trace should include normalized PTX function histogram");
        assertTrue(diag.contains("\"ptxAliasHistogram\""), "print-centric trace should include PTX alias histogram");
        assertTrue(diag.contains("\"pdfTextOperandHistogram\""), "print-centric trace should include PDF text operand histogram");
        assertTrue(diag.contains("\"crossExam\""), "print-centric trace should include AFP-vs-PDF cross exam");
        assertTrue(diag.contains("\"codePageMappingMatrix\""), "diag should include code page mapping matrix");
        assertTrue(diag.contains("\"semanticFallback\""), "diag should include semantic fallback diagnostics");
        assertTrue(diag.contains("\"fontResolution\""), "diag should include font resolution details");
        assertTrue(diag.contains("\"resolvedUsageEvents\""), "diag should include resolved scoped usage events");
        assertTrue(diag.contains("\"unresolvedUsageEvents\""), "diag should include unresolved scoped usage events");
        assertTrue(diag.contains("\"imageDecode\""), "diag should include image decode details");
        assertTrue(diag.contains("\"resourceResolution\""), "diag should include resource resolution trace");
    }

    @Test
    void supportsStrictMetadataStyleMode(@TempDir Path tempDir) throws Exception {
        Path afpFile = tempDir.resolve("input.afp");
        Files.write(afpFile, concat(
            sf("D3A8A8", new byte[0]),
            sf("D3A8AF", new byte[0]),
            sf("D3EE9B", ptxCs((byte) 0xDA, "HELLO AFP".getBytes(StandardCharsets.ISO_8859_1))),
            sf("D3A9AF", new byte[0]),
            sf("D3A9A8", new byte[0])
        ));
        Path workspace = tempDir.resolve("work");
        Files.createDirectories(workspace);

        InProcAfpEngineAdapter adapter = new InProcAfpEngineAdapter("pdfbox", Map.of());
        EngineOutputs outputs = adapter.convertToWorkspace(
            new EngineInputs(
                afpFile,
                "input.afp",
                new ResourceContext(java.util.List.of(), Optional.empty(), false),
                new ConversionOptions(
                    new TextPolicy(TextFallback.SUBSTITUTE, true),
                    new FontPolicy("default"),
                    new GeometryPolicy("default"),
                    new MetadataPolicy("strict"),
                    true
                ),
                new Limits(10_000, 100, Duration.ofSeconds(5), 1000, 1024 * 1024),
                Optional.empty()
            ),
            workspace
        );

        String meta = Files.readString(outputs.metaJsonPath());
        assertTrue(meta.contains("\"metadataMode\": \"strict\""), "meta should expose strict metadata mode");
        assertTrue(meta.contains("\"styleMode\": \"strict\""), "afpStructure should expose strict style mode");
        assertTrue(meta.contains("\"source\": \"strict\""), "strict mode should mark style source as strict");
        assertTrue(meta.contains("\"fontWeightHint\": \"unknown\""), "strict mode should avoid inferred font weight");
        assertTrue(meta.contains("\"uppercaseRatio\": -1"), "strict mode should avoid inferred uppercase ratio");
    }

    @Test
    void appliesRenderAndDiagnosticsPolicies(@TempDir Path tempDir) throws Exception {
        Path afpFile = tempDir.resolve("input.afp");
        Files.write(afpFile, concat(
            sf("D3A8A8", new byte[0]),
            sf("D3A8AF", new byte[0]),
            sf("D3EE9B", ptxCs((byte) 0xDA, "HELLO AFP".getBytes(StandardCharsets.ISO_8859_1))),
            sf("D3A9AF", new byte[0]),
            sf("D3A9A8", new byte[0])
        ));
        Path workspace = tempDir.resolve("work");
        Files.createDirectories(workspace);

        InProcAfpEngineAdapter adapter = new InProcAfpEngineAdapter("pdfbox", Map.of());
        EngineOutputs outputs = adapter.convertToWorkspace(
            new EngineInputs(
                afpFile,
                "input.afp",
                new ResourceContext(java.util.List.of(), Optional.empty(), false),
                new ConversionOptions(
                    new TextPolicy(TextFallback.SUBSTITUTE, true),
                    new FontPolicy("default"),
                    new GeometryPolicy("default"),
                    new MetadataPolicy("strict"),
                    new RenderPolicy("styled-semantic", "balanced", "embedded-only"),
                    new DiagnosticsPolicy("minimal"),
                    true
                ),
                new Limits(10_000, 100, Duration.ofSeconds(5), 1000, 1024 * 1024),
                Optional.empty()
            ),
            workspace
        );

        String meta = Files.readString(outputs.metaJsonPath());
        String diag = Files.readString(outputs.diagJsonPath());
        assertTrue(meta.contains("\"mode\": \"styled-semantic\""), "meta should persist selected render mode");
        assertTrue(meta.contains("\"fidelityLevel\": \"balanced\""), "meta should persist selected fidelity level");
        assertTrue(meta.contains("\"resourcePolicy\": \"embedded-only\""), "meta should persist selected resource policy");
        assertTrue(meta.contains("\"verbosity\": \"minimal\""), "meta should persist diagnostics verbosity");
        assertTrue(diag.contains("\"renderMode\": \"styled-semantic\""), "diag should persist render mode");
        assertTrue(diag.contains("\"diagVerbosity\": \"minimal\""), "diag should persist diagnostics verbosity");
    }

    @Test
    void emitsImageDecodeDiagnosticsForEmbeddedPayload(@TempDir Path tempDir) throws Exception {
        byte[] tinyPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII="
        );
        Path afpFile = tempDir.resolve("input.afp");
        Files.write(afpFile, concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3A8C9", new byte[0]), // BIM
            sf("D3ABC3", tinyPng),     // image-support payload
            sf("D3A9C9", new byte[0]), // EIM
            sf("D3EE9B", ptxCs((byte) 0xDA, "IMG".getBytes(StandardCharsets.ISO_8859_1))),
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        ));
        Path workspace = tempDir.resolve("work");
        Files.createDirectories(workspace);

        InProcAfpEngineAdapter adapter = new InProcAfpEngineAdapter("pdfbox", Map.of());
        EngineOutputs outputs = adapter.convertToWorkspace(
            new EngineInputs(
                afpFile,
                "input.afp",
                new ResourceContext(java.util.List.of(), Optional.empty(), false),
                new ConversionOptions(
                    new TextPolicy(TextFallback.SUBSTITUTE, true),
                    new FontPolicy("default"),
                    new GeometryPolicy("default"),
                    new MetadataPolicy("strict"),
                    true
                ),
                new Limits(10_000, 100, Duration.ofSeconds(5), 1000, 1024 * 1024),
                Optional.empty()
            ),
            workspace
        );

        String diag = Files.readString(outputs.diagJsonPath());
        assertTrue(diag.contains("\"imageObjectCount\": 1"), "diag should report one image object");
        assertTrue(diag.contains("\"decodedDirectCount\": 1"), "diag should report direct image decode");
        assertTrue(diag.contains("\"resourceResolution\""), "diag should include resource resolution trace");
    }

    @Test
    void emitsUnresolvedImageHintTraceForDescriptorOnlyPayload(@TempDir Path tempDir) throws Exception {
        byte[] hintPayload = utf16BeAscii("Arial Bold");
        Path afpFile = tempDir.resolve("input.afp");
        Files.write(afpFile, concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3A8C9", new byte[0]), // BIM
            sf("D3ABC3", hintPayload), // descriptor-like payload, not raster
            sf("D3A9C9", new byte[0]), // EIM
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        ));
        Path workspace = tempDir.resolve("work");
        Files.createDirectories(workspace);

        InProcAfpEngineAdapter adapter = new InProcAfpEngineAdapter("pdfbox", Map.of());
        EngineOutputs outputs = adapter.convertToWorkspace(
            new EngineInputs(
                afpFile,
                "input.afp",
                new ResourceContext(java.util.List.of(), Optional.empty(), false),
                new ConversionOptions(
                    new TextPolicy(TextFallback.SUBSTITUTE, true),
                    new FontPolicy("default"),
                    new GeometryPolicy("default"),
                    new MetadataPolicy("strict"),
                    true
                ),
                new Limits(10_000, 100, Duration.ofSeconds(5), 1000, 1024 * 1024),
                Optional.empty()
            ),
            workspace
        );

        String diag = Files.readString(outputs.diagJsonPath());
        assertTrue(diag.contains("\"imageResourceHintTrace\""), "diag should expose image hint trace");
        assertTrue(diag.contains("\"unresolvedHintedImageObjects\": 1"), "diag should report unresolved hinted image");
        assertTrue(diag.contains("\"filteredFontHintImageObjects\": 1"), "diag should report filtered font-like hints");
        assertTrue(diag.contains("\"pageSummaries\""), "diag should expose per-page hint summary");
    }

    @Test
    void tracesMixedResolvedAndUnresolvedImageHints(@TempDir Path tempDir) throws Exception {
        byte[] tinyPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII="
        );
        Path resources = tempDir.resolve("resources");
        Files.createDirectories(resources);
        Files.write(resources.resolve("match-image-001.png"), tinyPng);

        Path afpFile = tempDir.resolve("input.afp");
        Files.write(afpFile, concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3A8C9", new byte[0]), // BIM #1
            sf("D3ABC3", utf16BeAscii("match image 001")),
            sf("D3A9C9", new byte[0]), // EIM #1
            sf("D3A8C9", new byte[0]), // BIM #2
            sf("D3ABC3", utf16BeAscii("missing image 002")),
            sf("D3A9C9", new byte[0]), // EIM #2
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        ));
        Path workspace = tempDir.resolve("work");
        Files.createDirectories(workspace);

        InProcAfpEngineAdapter adapter = new InProcAfpEngineAdapter("pdfbox", Map.of());
        EngineOutputs outputs = adapter.convertToWorkspace(
            new EngineInputs(
                afpFile,
                "input.afp",
                new ResourceContext(java.util.List.of(resources), Optional.empty(), false),
                new ConversionOptions(
                    new TextPolicy(TextFallback.SUBSTITUTE, true),
                    new FontPolicy("default"),
                    new GeometryPolicy("default"),
                    new MetadataPolicy("strict"),
                    true
                ),
                new Limits(10_000, 100, Duration.ofSeconds(5), 1000, 1024 * 1024),
                Optional.empty()
            ),
            workspace
        );

        String diag = Files.readString(outputs.diagJsonPath());
        assertTrue(diag.contains("\"hintedImageObjects\": 2"), "diag should report two hinted image objects");
        assertTrue(diag.contains("\"resolvedHintedImageObjects\": 1"), "diag should report one resolved hinted image");
        assertTrue(diag.contains("\"unresolvedHintedImageObjects\": 1"), "diag should report one unresolved hinted image");
    }

    private static byte[] sf(String sfIdHex, byte[] payload) {
        byte[] sfId = HexFormat.of().parseHex(sfIdHex);
        int length = 8 + payload.length;
        byte[] bytes = new byte[1 + length];
        bytes[0] = 0x5A;
        bytes[1] = (byte) ((length >>> 8) & 0xFF);
        bytes[2] = (byte) (length & 0xFF);
        bytes[3] = sfId[0];
        bytes[4] = sfId[1];
        bytes[5] = sfId[2];
        bytes[6] = 0x00;
        bytes[7] = 0x00;
        bytes[8] = 0x00;
        System.arraycopy(payload, 0, bytes, 9, payload.length);
        return bytes;
    }

    private static byte[] concat(byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, pos, chunk.length);
            pos += chunk.length;
        }
        return out;
    }

    private static byte[] ptxCs(byte fn, byte[] data) {
        int length = 3 + data.length;
        byte[] out = new byte[length];
        out[0] = 0x2B;
        out[1] = (byte) (length & 0xFF);
        out[2] = fn;
        System.arraycopy(data, 0, out, 3, data.length);
        return out;
    }

    private static byte[] utf16BeAscii(String value) {
        byte[] chars = value.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[chars.length * 2];
        for (int i = 0; i < chars.length; i++) {
            out[i * 2] = 0x00;
            out[(i * 2) + 1] = chars[i];
        }
        return out;
    }
}
