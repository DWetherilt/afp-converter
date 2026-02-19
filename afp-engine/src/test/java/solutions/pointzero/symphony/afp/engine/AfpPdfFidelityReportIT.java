package solutions.pointzero.symphony.afp.engine;

import solutions.pointzero.symphony.afp.api.ConversionOptions;
import solutions.pointzero.symphony.afp.api.FontPolicy;
import solutions.pointzero.symphony.afp.api.GeometryPolicy;
import solutions.pointzero.symphony.afp.api.Limits;
import solutions.pointzero.symphony.afp.api.MetadataPolicy;
import solutions.pointzero.symphony.afp.api.ResourceContext;
import solutions.pointzero.symphony.afp.api.TextFallback;
import solutions.pointzero.symphony.afp.api.TextPolicy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AfpPdfFidelityReportIT {
    @Test
    void generatesFidelityReport(@TempDir Path tempDir) throws Exception {
        List<CorpusEntry> corpus = loadCorpusEntries();
        assertTrue(!corpus.isEmpty(), "Fidelity corpus is empty");

        InProcAfpEngineAdapter adapter = new InProcAfpEngineAdapter("pdfbox", java.util.Map.of());
        List<CaseReport> cases = new ArrayList<>();
        int caseIndex = 0;

        for (CorpusEntry entry : corpus) {
            assertTrue(Files.exists(entry.afpPath), "Missing corpus AFP at " + entry.afpPath);
            assertTrue(Files.exists(entry.expectedPdfPath), "Missing corpus expected PDF at " + entry.expectedPdfPath);

            Path caseWorkspace = tempDir.resolve("case-" + caseIndex++);
            Files.createDirectories(caseWorkspace);
            EngineOutputs outputs = adapter.convertToWorkspace(
                new EngineInputs(
                    entry.afpPath,
                    entry.afpPath.getFileName().toString(),
                    new ResourceContext(java.util.List.of(), Optional.empty(), false),
                    new ConversionOptions(
                        new TextPolicy(TextFallback.SUBSTITUTE, true),
                        new FontPolicy("default"),
                        new GeometryPolicy("default"),
                        new MetadataPolicy("inferred"),
                        true
                    ),
                    new Limits(64L * 1024 * 1024, 10_000, Duration.ofSeconds(120), 100_000, 512L * 1024 * 1024),
                    Optional.empty()
                ),
                caseWorkspace
            );

            Path actualPdfPath = outputs.pdfPath();
            assertTrue(Files.exists(actualPdfPath), "Generated PDF missing: " + actualPdfPath);

            try (PDDocument expected = PDDocument.load(entry.expectedPdfPath.toFile());
                 PDDocument actual = PDDocument.load(actualPdfPath.toFile())) {
                CaseReport report = compare(entry.label, entry.afpPath, entry.expectedPdfPath, expected, actual);
                cases.add(report);
            }
        }

        ReportData report = aggregate(cases);
        Path reportPath = resolveReportPath();
        Path parent = reportPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(reportPath, report.toJson(), java.nio.charset.StandardCharsets.UTF_8);
        String written = Files.readString(reportPath);

        assertTrue(Files.exists(reportPath), "Expected fidelity report at " + reportPath);
        assertTrue(report.comparedPages > 0, "No pages were compared");
        assertTrue(written.contains("\"corpus\": \"docs/fidelity-corpus.txt\""), "report should include corpus source path");
        assertTrue(written.contains("\"cases\": ["), "report should include per-case metrics");
    }

    private static CaseReport compare(String label, Path afpPath, Path expectedPdfPath, PDDocument expected, PDDocument actual) throws Exception {
        int expectedPages = expected.getNumberOfPages();
        int actualPages = actual.getNumberOfPages();
        int comparedPages = Math.min(expectedPages, actualPages);

        PDFRenderer expectedRenderer = new PDFRenderer(expected);
        PDFRenderer actualRenderer = new PDFRenderer(actual);

        List<PageMetrics> pages = new ArrayList<>();
        double pixelDiffSum = 0.0;
        double sizePenaltySum = 0.0;
        double meanAbsSum = 0.0;

        for (int i = 0; i < comparedPages; i++) {
            BufferedImage expectedImage = expectedRenderer.renderImageWithDPI(i, 144);
            BufferedImage actualImage = actualRenderer.renderImageWithDPI(i, 144);
            PageMetrics metrics = comparePage(i + 1, expectedImage, actualImage);
            pages.add(metrics);
            pixelDiffSum += metrics.pixelDiffRatio;
            sizePenaltySum += metrics.sizePenaltyRatio;
            meanAbsSum += metrics.meanAbsoluteChannelDelta;
        }

        Set<String> expectedTokens = tokenize(extractText(expected));
        Set<String> actualTokens = tokenize(extractText(actual));
        long matched = expectedTokens.stream().filter(actualTokens::contains).count();
        double tokenRecall = expectedTokens.isEmpty() ? 0.0 : matched / (double) expectedTokens.size();
        double tokenPrecision = actualTokens.isEmpty() ? 0.0 : matched / (double) actualTokens.size();

        double avgPixelDiff = comparedPages == 0 ? 1.0 : pixelDiffSum / comparedPages;
        double avgSizePenalty = comparedPages == 0 ? 1.0 : sizePenaltySum / comparedPages;
        double avgMeanAbs = comparedPages == 0 ? 1.0 : meanAbsSum / comparedPages;
        double pageCountPenalty = expectedPages == 0 ? 1.0 : Math.abs(expectedPages - actualPages) / (double) expectedPages;
        double weightedError = (avgPixelDiff * 0.60) + (avgSizePenalty * 0.20) + (avgMeanAbs * 0.10) + (pageCountPenalty * 0.10);
        double fidelityScore = Math.max(0.0, 1.0 - weightedError);

        return new CaseReport(
            label,
            afpPath,
            expectedPdfPath,
            expectedPages,
            actualPages,
            comparedPages,
            avgPixelDiff,
            avgSizePenalty,
            avgMeanAbs,
            pageCountPenalty,
            fidelityScore,
            expectedTokens.size(),
            actualTokens.size(),
            matched,
            tokenRecall,
            tokenPrecision,
            pages
        );
    }

    private static ReportData aggregate(List<CaseReport> cases) {
        if (cases.isEmpty()) {
            return new ReportData(Instant.now().toString(), 0, 0, 0, 0, 1.0, 1.0, 1.0, 1.0, 0.0, 0, 0, 0L, 0.0, 0.0, List.of(), List.of());
        }
        int expectedPages = 0;
        int actualPages = 0;
        int comparedPages = 0;
        double pixelDiffSum = 0.0;
        double sizePenaltySum = 0.0;
        double meanAbsSum = 0.0;
        double pagePenaltySum = 0.0;
        double scoreSum = 0.0;
        int expectedTokens = 0;
        int actualTokens = 0;
        long matchedTokens = 0;
        List<PageMetrics> allPages = new ArrayList<>();
        for (CaseReport c : cases) {
            expectedPages += c.expectedPageCount;
            actualPages += c.actualPageCount;
            comparedPages += c.comparedPages;
            pixelDiffSum += c.averagePixelDiffRatio;
            sizePenaltySum += c.averageSizePenaltyRatio;
            meanAbsSum += c.averageMeanAbsoluteChannelDelta;
            pagePenaltySum += c.pageCountPenaltyRatio;
            scoreSum += c.fidelityScore;
            expectedTokens += c.expectedTokenCount;
            actualTokens += c.actualTokenCount;
            matchedTokens += c.matchedTokenCount;
            allPages.addAll(c.pages);
        }
        int count = cases.size();
        double tokenRecall = expectedTokens == 0 ? 0.0 : matchedTokens / (double) expectedTokens;
        double tokenPrecision = actualTokens == 0 ? 0.0 : matchedTokens / (double) actualTokens;
        return new ReportData(
            Instant.now().toString(),
            count,
            expectedPages,
            actualPages,
            comparedPages,
            pixelDiffSum / count,
            sizePenaltySum / count,
            meanAbsSum / count,
            pagePenaltySum / count,
            scoreSum / count,
            expectedTokens,
            actualTokens,
            matchedTokens,
            tokenRecall,
            tokenPrecision,
            cases,
            allPages
        );
    }

    private static PageMetrics comparePage(int pageNumber, BufferedImage expected, BufferedImage actual) {
        int width = Math.min(expected.getWidth(), actual.getWidth());
        int height = Math.min(expected.getHeight(), actual.getHeight());
        long overlapPixels = (long) width * height;
        long expectedPixels = (long) expected.getWidth() * expected.getHeight();
        long actualPixels = (long) actual.getWidth() * actual.getHeight();
        long maxPixels = Math.max(expectedPixels, actualPixels);

        long changed = 0;
        long absDeltaSum = 0;
        int tolerance = 12;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgbA = expected.getRGB(x, y);
                int rgbB = actual.getRGB(x, y);

                int dr = Math.abs(((rgbA >>> 16) & 0xFF) - ((rgbB >>> 16) & 0xFF));
                int dg = Math.abs(((rgbA >>> 8) & 0xFF) - ((rgbB >>> 8) & 0xFF));
                int db = Math.abs((rgbA & 0xFF) - (rgbB & 0xFF));
                absDeltaSum += dr + dg + db;
                if (dr > tolerance || dg > tolerance || db > tolerance) {
                    changed++;
                }
            }
        }

        double pixelDiffRatio = overlapPixels == 0 ? 1.0 : changed / (double) overlapPixels;
        double meanAbsoluteChannelDelta = overlapPixels == 0
            ? 1.0
            : absDeltaSum / (double) (overlapPixels * 3L * 255L);
        double sizePenaltyRatio = maxPixels == 0 ? 1.0 : 1.0 - (overlapPixels / (double) maxPixels);

        return new PageMetrics(
            pageNumber,
            expected.getWidth(),
            expected.getHeight(),
            actual.getWidth(),
            actual.getHeight(),
            pixelDiffRatio,
            meanAbsoluteChannelDelta,
            sizePenaltyRatio
        );
    }

    private static String extractText(PDDocument document) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        return stripper.getText(document);
    }

    private static Set<String> tokenize(String text) {
        return List.of(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
            .stream()
            .map(String::trim)
            .filter(token -> token.length() >= 3)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Path resolveReportPath() {
        String configured = System.getProperty("afp.fidelity.reportPath", "").trim();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("..", "preview", "fidelity-report.json").toAbsolutePath().normalize();
    }

    private static List<CorpusEntry> loadCorpusEntries() throws Exception {
        Path corpusFile = Path.of("..", "docs", "fidelity-corpus.txt").normalize();
        if (!Files.exists(corpusFile)) {
            return List.of(new CorpusEntry(
                "sample",
                Path.of("..", "sampleData", "sample.afp").normalize(),
                Path.of("..", "sampleOutput", "sample.pdf").normalize()
            ));
        }
        List<CorpusEntry> entries = new ArrayList<>();
        for (String raw : Files.readAllLines(corpusFile)) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = Arrays.stream(line.split("\\|", -1)).map(String::trim).toArray(String[]::new);
            if (parts.length < 3) {
                continue;
            }
            entries.add(new CorpusEntry(
                parts[0],
                Path.of("..", parts[1]).normalize(),
                Path.of("..", parts[2]).normalize()
            ));
        }
        return entries;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private record CorpusEntry(String label, Path afpPath, Path expectedPdfPath) {
    }

    private record CaseReport(String label,
                              Path afpPath,
                              Path expectedPdfPath,
                              int expectedPageCount,
                              int actualPageCount,
                              int comparedPages,
                              double averagePixelDiffRatio,
                              double averageSizePenaltyRatio,
                              double averageMeanAbsoluteChannelDelta,
                              double pageCountPenaltyRatio,
                              double fidelityScore,
                              int expectedTokenCount,
                              int actualTokenCount,
                              long matchedTokenCount,
                              double tokenRecall,
                              double tokenPrecision,
                              List<PageMetrics> pages) {
        private String toJson() {
            return "{"
                + "\"label\": \"" + safe(label) + "\", "
                + "\"afp\": \"" + safe(afpPath.toString().replace('\\', '/')) + "\", "
                + "\"expectedPdf\": \"" + safe(expectedPdfPath.toString().replace('\\', '/')) + "\", "
                + "\"expectedPageCount\": " + expectedPageCount + ", "
                + "\"actualPageCount\": " + actualPageCount + ", "
                + "\"comparedPages\": " + comparedPages + ", "
                + "\"fidelityScore\": " + f(fidelityScore) + ", "
                + "\"averagePixelDiffRatio\": " + f(averagePixelDiffRatio) + ", "
                + "\"averageSizePenaltyRatio\": " + f(averageSizePenaltyRatio) + ", "
                + "\"averageMeanAbsoluteChannelDelta\": " + f(averageMeanAbsoluteChannelDelta) + ", "
                + "\"pageCountPenaltyRatio\": " + f(pageCountPenaltyRatio) + ", "
                + "\"expectedTokenCount\": " + expectedTokenCount + ", "
                + "\"actualTokenCount\": " + actualTokenCount + ", "
                + "\"matchedTokenCount\": " + matchedTokenCount + ", "
                + "\"tokenRecall\": " + f(tokenRecall) + ", "
                + "\"tokenPrecision\": " + f(tokenPrecision)
                + "}";
        }
    }

    private record PageMetrics(int pageNumber,
                               int expectedWidth,
                               int expectedHeight,
                               int actualWidth,
                               int actualHeight,
                               double pixelDiffRatio,
                               double meanAbsoluteChannelDelta,
                               double sizePenaltyRatio) {
        private String toJson() {
            return "{"
                + "\"page\": " + pageNumber + ", "
                + "\"expected\": {\"width\": " + expectedWidth + ", \"height\": " + expectedHeight + "}, "
                + "\"actual\": {\"width\": " + actualWidth + ", \"height\": " + actualHeight + "}, "
                + "\"pixelDiffRatio\": " + f(pixelDiffRatio) + ", "
                + "\"meanAbsoluteChannelDelta\": " + f(meanAbsoluteChannelDelta) + ", "
                + "\"sizePenaltyRatio\": " + f(sizePenaltyRatio)
                + "}";
        }
    }

    private record ReportData(String generatedAt,
                              int comparedCaseCount,
                              int expectedPageCount,
                              int actualPageCount,
                              int comparedPages,
                              double averagePixelDiffRatio,
                              double averageSizePenaltyRatio,
                              double averageMeanAbsoluteChannelDelta,
                              double pageCountPenaltyRatio,
                              double fidelityScore,
                              int expectedTokenCount,
                              int actualTokenCount,
                              long matchedTokenCount,
                              double tokenRecall,
                              double tokenPrecision,
                              List<CaseReport> cases,
                              List<PageMetrics> pages) {
        private String toJson() {
            String casesJson = cases.stream().map(CaseReport::toJson).collect(Collectors.joining(", "));
            String pagesJson = pages.stream().map(PageMetrics::toJson).collect(Collectors.joining(", "));
            return "{\n"
                + "  \"schemaVersion\": \"1\",\n"
                + "  \"generatedAt\": \"" + safe(generatedAt) + "\",\n"
                + "  \"inputs\": {\n"
                + "    \"corpus\": \"docs/fidelity-corpus.txt\",\n"
                + "    \"cases\": " + comparedCaseCount + "\n"
                + "  },\n"
                + "  \"summary\": {\n"
                + "    \"expectedPageCount\": " + expectedPageCount + ",\n"
                + "    \"actualPageCount\": " + actualPageCount + ",\n"
                + "    \"comparedPages\": " + comparedPages + ",\n"
                + "    \"fidelityScore\": " + f(fidelityScore) + ",\n"
                + "    \"averagePixelDiffRatio\": " + f(averagePixelDiffRatio) + ",\n"
                + "    \"averageSizePenaltyRatio\": " + f(averageSizePenaltyRatio) + ",\n"
                + "    \"averageMeanAbsoluteChannelDelta\": " + f(averageMeanAbsoluteChannelDelta) + ",\n"
                + "    \"pageCountPenaltyRatio\": " + f(pageCountPenaltyRatio) + "\n"
                + "  },\n"
                + "  \"text\": {\n"
                + "    \"expectedTokenCount\": " + expectedTokenCount + ",\n"
                + "    \"actualTokenCount\": " + actualTokenCount + ",\n"
                + "    \"matchedTokenCount\": " + matchedTokenCount + ",\n"
                + "    \"tokenRecall\": " + f(tokenRecall) + ",\n"
                + "    \"tokenPrecision\": " + f(tokenPrecision) + "\n"
                + "  },\n"
                + "  \"cases\": [\n    " + casesJson + "\n  ],\n"
                + "  \"pages\": [\n    " + pagesJson + "\n  ]\n"
                + "}\n";
        }
    }
}
