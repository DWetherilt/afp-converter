package com.upland.connect.afp.engine;

import java.io.IOException;
import java.nio.file.Path;

public interface PdfWriter {
    void writeSinglePageTextPdf(Path outputPath, String text) throws IOException;

    default void writeHtmlPdf(Path outputPath, String html) throws IOException {
        writeSinglePageTextPdf(outputPath, html);
    }

    default void writeLayoutPdf(Path outputPath,
                                AfpDocumentLayout layout,
                                String sourceLabel,
                                int structuredFieldCount,
                                int pageCount) throws IOException {
        writeSinglePageTextPdf(outputPath, layout.title());
    }
}
