package solutions.pointzero.symphony.afp.engine;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfpPrintCentricTraceEngineTest {
    @Test
    void correlatesAfpTextSignalsWithPdfTextOperators(@TempDir Path tempDir) throws Exception {
        Path pdf = tempDir.resolve("out.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 700);
                content.showText("HELLO");
                content.endText();
            }
            doc.save(pdf.toFile());
        }

        byte[] ptx = ptxCs((byte) 0xDA, "HELLO".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        AfpStructuredField bpg = new AfpStructuredField(0, 8, "D3A8AF", 0, 0, new byte[0]);
        AfpStructuredField ptxField = new AfpStructuredField(9, 8 + ptx.length, "D3EE9B", 0, ptx.length, ptx);
        AfpStructuredField epg = new AfpStructuredField(20 + ptx.length, 8, "D3A9AF", 0, 0, new byte[0]);
        AfpInterpretation interpretation = new AfpInterpretation(
            "trace.afp",
            0,
            3,
            1,
            0,
            List.of(),
            List.of(bpg, ptxField, epg),
            new AfpSemantics(0, 0, 1, 1, 1, 1, false, 0, "Cp500", "", "test", List.of(), List.of(), 0, 0, List.of(), List.of()),
            concat(
                sf("D3A8AF", new byte[0]),
                sf("D3EE9B", ptx),
                sf("D3A9AF", new byte[0])
            )
        );

        AfpPrintCentricTraceEngine.Summary summary = AfpPrintCentricTraceEngine.analyze(interpretation, pdf, 32);

        assertTrue(summary.crossExam().afpTextSignalCount() > 0, "expected AFP PTX signal count");
        assertTrue(summary.crossExam().pdfTextOperatorCount() > 0, "expected PDF text operators");
        assertFalse(summary.pdfOperatorHistogram().isEmpty(), "expected PDF operator histogram entries");
        assertTrue(summary.crossExam().inferenceHints().contains("text-signal-correlation-observed"),
            "expected text correlation inference");
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
}
