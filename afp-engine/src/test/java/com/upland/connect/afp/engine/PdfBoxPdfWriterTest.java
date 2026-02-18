package com.upland.connect.afp.engine;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxPdfWriterTest {
    @Test
    void writesTextBasedPdf(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("sample.pdf");
        String expectedText = "Hello AFP";

        PdfWriter writer = new PdfBoxPdfWriter();
        writer.writeSinglePageTextPdf(output, expectedText);

        assertTrue(Files.exists(output), "pdf should be created");
        assertTrue(Files.size(output) > 0, "pdf should not be empty");

        try (var document = PDDocument.load(output.toFile())) {
            String extracted = new PDFTextStripper().getText(document);
            assertTrue(extracted.contains(expectedText), "generated PDF should contain searchable text");
        }
    }
}
