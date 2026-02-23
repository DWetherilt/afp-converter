package solutions.pointzero.symphony.afp.engine;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfpNativePdfRendererTest {
    @Test
    void paintOrderSortsByDepthThenSequenceThenKind() {
        List<String> order = AfpNativePdfRenderer.buildPaintOrderSignatureForTest(
            List.of(
                new int[] {0, 1, 2}, // text base depth 1 seq 2
                new int[] {0, 0, 3}  // text base depth 0 seq 3
            ),
            List.of(
                new int[] {0, 0, 1}, // image base depth 0 seq 1
                new int[] {1, 0, 2}  // image overlay depth 0 seq 2
            ),
            List.of(
                new int[] {0, 0, 1}  // graphic base depth 0 seq 1
            )
        );

        assertEquals(
            List.of(
                "IMAGE|overlay=false|depth=0|seq=1",
                "GRAPHIC|overlay=false|depth=0|seq=1",
                "IMAGE|overlay=true|depth=0|seq=2",
                "TEXT|overlay=false|depth=0|seq=3",
                "TEXT|overlay=false|depth=1|seq=2"
            ),
            order
        );
    }

    @Test
    void paintOrderIsDeterministicForOverlayTextImageInterleave() {
        List<String> order = AfpNativePdfRenderer.buildPaintOrderSignatureForTest(
            List.of(
                new int[] {0, 0, 10}, // base text
                new int[] {1, 0, 12}  // overlay text
            ),
            List.of(
                new int[] {0, 0, 11}, // base image between text sequences
                new int[] {1, 0, 11}  // overlay image between overlay text sequences
            ),
            List.of()
        );

        assertEquals(
            List.of(
                "TEXT|overlay=false|depth=0|seq=10",
                "IMAGE|overlay=false|depth=0|seq=11",
                "IMAGE|overlay=true|depth=0|seq=11",
                "TEXT|overlay=true|depth=0|seq=12"
            ),
            order
        );
    }

    @Test
    void rendersMultiplePagesFromBpgMarkers(@TempDir Path tempDir) throws Exception {
        byte[] afp = concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3EE9B", ptxCs((byte) 0xDA, "PAGE ONE".getBytes(StandardCharsets.ISO_8859_1))),
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3EE9B", ptxCs((byte) 0xDA, "PAGE TWO".getBytes(StandardCharsets.ISO_8859_1))),
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        );
        AfpInterpretation interpretation = new AfpInterpreter().interpret(afp, "multi.afp");
        Path output = tempDir.resolve("out.pdf");

        new AfpNativePdfRenderer().render(output, interpretation);

        assertTrue(Files.exists(output), "native renderer should produce output pdf");
        try (PDDocument doc = PDDocument.load(output.toFile())) {
            assertEquals(2, doc.getNumberOfPages(), "renderer should preserve two-page AFP structure");
        }
    }

    @Test
    void usesSemanticFragmentsWhenPtxPayloadIsLowSignal(@TempDir Path tempDir) throws Exception {
        byte[] lowSignalPtx = ptxCs((byte) 0xDA, new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        AfpInterpretation interpretation = new AfpInterpretation(
            "semantic-fallback.afp",
            lowSignalPtx.length,
            1,
            1,
            0,
            List.of(),
            List.of(
                new AfpStructuredField(0, 8, "D3A8AF", 0, 0, new byte[0]), // BPG
                new AfpStructuredField(9, 8 + lowSignalPtx.length, "D3EE9B", 0, lowSignalPtx.length, lowSignalPtx), // PTX
                new AfpStructuredField(30, 8, "D3A9AF", 0, 0, new byte[0]) // EPG
            ),
            new AfpSemantics(
                0, 0, 1, 1, 1, 1, false, 0,
                "Cp500", "", "test",
                List.of(), List.of(), 0, 0, List.of(),
                List.of("John", "Doe", "Health", "Coverage", "Call", "Now")
            ),
            new byte[0]
        );

        Path output = tempDir.resolve("semantic.pdf");
        new AfpNativePdfRenderer().render(output, interpretation);

        try (PDDocument doc = PDDocument.load(output.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.toLowerCase().contains("john"), "expected semantic text to be rendered");
            assertTrue(text.toLowerCase().contains("coverage"), "expected semantic fallback fragments in output");
        }
    }

    @Test
    void usesSemanticFragmentsWhenDecodedRunIsImplausiblyShort(@TempDir Path tempDir) throws Exception {
        byte[] shortDecodedPtx = ptxCs((byte) 0xDA, "Ke".getBytes(StandardCharsets.ISO_8859_1));
        AfpInterpretation interpretation = new AfpInterpretation(
            "semantic-short-fallback.afp",
            shortDecodedPtx.length,
            1,
            1,
            0,
            List.of(),
            List.of(
                new AfpStructuredField(0, 8, "D3A8AF", 0, 0, new byte[0]), // BPG
                new AfpStructuredField(9, 8 + shortDecodedPtx.length, "D3EE9B", 0, shortDecodedPtx.length, shortDecodedPtx), // PTX
                new AfpStructuredField(30, 8, "D3A9AF", 0, 0, new byte[0]) // EPG
            ),
            new AfpSemantics(
                0, 0, 1, 1, 1, 1, false, 0,
                "Cp500", "", "test",
                List.of(), List.of(), 0, 0, List.of(),
                List.of("John", "Doe", "Coverage")
            ),
            new byte[0]
        );

        Path output = tempDir.resolve("semantic-short.pdf");
        new AfpNativePdfRenderer().render(output, interpretation);

        try (PDDocument doc = PDDocument.load(output.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.toLowerCase().contains("john"), "expected semantic fallback when decoded run is implausibly short");
            assertTrue(text.toLowerCase().contains("coverage"), "expected semantic fragments to replace short decoded run");
        }
    }

    @Test
    void rendersDecodedImageObjectWhenEmbeddedImagePayloadExists(@TempDir Path tempDir) throws Exception {
        byte[] tinyPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII="
        );
        byte[] afp = concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3A8C9", new byte[0]), // BIM
            sf("D3ABC3", tinyPng),     // image-support/unknown payload inside image scope
            sf("D3A9C9", new byte[0]), // EIM
            sf("D3EE9B", ptxCs((byte) 0xDA, "IMG".getBytes(StandardCharsets.ISO_8859_1))), // PTX text to keep PTX render path active
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        );
        AfpInterpretation interpretation = new AfpInterpreter().interpret(afp, "image.afp");
        Path output = tempDir.resolve("image.pdf");

        new AfpNativePdfRenderer().render(output, interpretation);

        try (PDDocument doc = PDDocument.load(output.toFile())) {
            assertEquals(1, doc.getNumberOfPages(), "expected one rendered page");
            assertTrue(doc.getPage(0).getResources().getXObjectNames().iterator().hasNext(),
                "expected decoded image to be embedded as a PDF XObject");
        }
    }

    @Test
    void decodesMultipleEmbeddedImageFormats(@TempDir Path tempDir) throws Exception {
        byte[] png = encodeImage("png", false);
        byte[] jpg = encodeImage("jpg", false);
        byte[] gif = encodeImage("gif", false);
        byte[] bmp = encodeImage("bmp", false);
        byte[] afp = concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3A8C9", new byte[0]), // BIM
            sf("D3ABC3", png),
            sf("D3A9C9", new byte[0]), // EIM
            sf("D3A8C9", new byte[0]), // BIM
            sf("D3ABC3", jpg),
            sf("D3A9C9", new byte[0]), // EIM
            sf("D3A8C9", new byte[0]), // BIM
            sf("D3ABC3", gif),
            sf("D3A9C9", new byte[0]), // EIM
            sf("D3A8C9", new byte[0]), // BIM
            sf("D3ABC3", bmp),
            sf("D3A9C9", new byte[0]), // EIM
            sf("D3EE9B", ptxCs((byte) 0xDA, "IMG-FORMATS".getBytes(StandardCharsets.ISO_8859_1))),
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        );
        AfpInterpretation interpretation = new AfpInterpreter().interpret(afp, "image-formats.afp");
        Path output = tempDir.resolve("image-formats.pdf");

        new AfpNativePdfRenderer().render(output, interpretation);

        try (PDDocument doc = PDDocument.load(output.toFile())) {
            assertEquals(1, doc.getNumberOfPages(), "expected one rendered page");
            int xObjectCount = 0;
            for (var ignored : doc.getPage(0).getResources().getXObjectNames()) {
                xObjectCount++;
            }
            assertTrue(xObjectCount >= 3, "expected multiple decoded image formats to embed as XObjects");
        }
    }

    @Test
    void doesNotEmitPdfImageDrawOperatorForImageObjectWithoutDecodablePayload(@TempDir Path tempDir) throws Exception {
        byte[] afp = concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3A8C9", new byte[0]), // BIM
            sf("D3A9C9", new byte[0]), // EIM
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        );
        AfpInterpretation interpretation = new AfpInterpreter().interpret(afp, "image-empty.afp");
        Path output = tempDir.resolve("image-empty-do.pdf");

        new AfpNativePdfRenderer().render(output, interpretation);

        try (PDDocument doc = PDDocument.load(output.toFile())) {
            PDFStreamParser parser = new PDFStreamParser(doc.getPage(0));
            parser.parse();
            int doCount = 0;
            for (Object token : parser.getTokens()) {
                if (token instanceof Operator op && "Do".equals(op.getName())) {
                    doCount++;
                }
            }
            assertEquals(0, doCount, "expected no image draw operator for undecodable AFP image payload");
        }
    }

    @Test
    void treatsShortFormPtocaMoveFunctionsAsPositioningNotText(@TempDir Path tempDir) throws Exception {
        byte[] payload = concat(
            ptxCs((byte) 0x06, new byte[] {0x5A, 0x5A}), // short-form AMI alias; data bytes are printable "ZZ"
            ptxCs((byte) 0x08, new byte[] {0x00, 0x10}), // short-form RMI alias
            ptxCs((byte) 0x19, new byte[] {0x51, 0x51}), // short-form SBI alias; data bytes are printable "QQ"
            ptxCs((byte) 0x1D, new byte[] {0x4B, 0x4B}), // short-form SVI alias; data bytes are printable "KK"
            ptxCs((byte) 0xDB, "HELLO".getBytes(StandardCharsets.ISO_8859_1)) // observed TRN alias in sample
        );
        byte[] afp = concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3EE9B", payload),     // PTX raw payload
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        );
        AfpInterpretation interpretation = new AfpInterpreter().interpret(afp, "short-form-ptoca.afp");
        Path output = tempDir.resolve("short-form-ptoca.pdf");

        new AfpNativePdfRenderer().render(output, interpretation);

        try (PDDocument doc = PDDocument.load(output.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("HELLO"), "expected 0xDB TRN alias text to render");
            assertTrue(!text.contains("ZZ"), "expected short-form AMI alias data not to be decoded as text");
            assertTrue(!text.contains("QQ"), "expected short-form SBI alias data not to be decoded as text");
            assertTrue(!text.contains("KK"), "expected short-form SVI alias data not to be decoded as text");
        }
    }

    private static byte[] encodeImage(String format, boolean alpha) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLACK.getRGB());
        image.setRGB(1, 0, Color.WHITE.getRGB());
        image.setRGB(0, 1, new Color(0, 120, 220).getRGB());
        image.setRGB(1, 1, new Color(220, 70, 40).getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean encoded = ImageIO.write(image, format, out);
        assertTrue(encoded, "Expected JDK ImageIO writer for format: " + format);
        return out.toByteArray();
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
