package solutions.pointzero.symphony.afp.engine;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PdfBoxPdfWriter implements PdfWriter {
    private static final String FALLBACK = "?";

    @Override
    public void writeSinglePageTextPdf(Path outputPath, String text) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                PDType1Font font = PDType1Font.HELVETICA;
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(72, 720);
                String[] lines = (text == null ? "" : text).split("\\R", -1);
                boolean first = true;
                for (String line : lines) {
                    if (!first) {
                        content.newLineAtOffset(0, -14);
                    }
                    content.showText(sanitizeForFont(font, line));
                    first = false;
                }
                content.endText();
            }

            document.save(outputPath.toFile());
        }
    }

    @Override
    public void writeHtmlPdf(Path outputPath, String html) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (OutputStream out = Files.newOutputStream(outputPath)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html == null ? "" : html, null);
            builder.toStream(out);
            builder.run();
        } catch (Exception e) {
            throw new IOException("Failed to render HTML to PDF", e);
        }
    }

    @Override
    public void writeLayoutPdf(Path outputPath,
                               AfpDocumentLayout layout,
                               String sourceLabel,
                               int structuredFieldCount,
                               int pageCount) throws IOException {
        String html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="UTF-8" /><title>%s</title></head>
            <body><h1>%s</h1><p>source=%s | fields=%d | pages=%d</p></body></html>
            """.formatted(
            escapeHtml(layout.title()),
            escapeHtml(layout.title()),
            escapeHtml(sourceLabel == null ? "" : sourceLabel),
            structuredFieldCount,
            pageCount);
        writeHtmlPdf(outputPath, html);
    }

    private static String sanitizeForFont(PDType1Font font, String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (Exception ignored) {
                sb.append(FALLBACK);
            }
        }
        return sb.toString();
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
