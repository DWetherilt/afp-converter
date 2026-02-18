package com.upland.connect.afp.engine;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfpInterpreterTest {
    @Test
    void decodesBasicDocumentPageAndTextSemantics() {
        byte[] afp = concat(
            sf("D3A8A8", new byte[0]), // BDT
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3EE9B", ptxCs((byte) 0xDA, "HELLO AFP".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))),
            sf("D3A9AF", new byte[0]), // EPG
            sf("D3A9A8", new byte[0])  // EDT
        );

        AfpInterpretation interpretation = new AfpInterpreter().interpret(afp, "sample.afp");

        assertEquals(5, interpretation.structuredFieldCount());
        assertEquals(1, interpretation.pageCount());
        assertEquals(1, interpretation.semantics().beginDocumentCount());
        assertEquals(1, interpretation.semantics().endDocumentCount());
        assertEquals(1, interpretation.semantics().beginPageCount());
        assertEquals(1, interpretation.semantics().endPageCount());
        assertEquals(1, interpretation.semantics().textObjectCount());
        assertEquals(1, interpretation.semantics().ptxControlSequenceCount());
        assertTrue(interpretation.semantics().textFragments().stream().anyMatch(s -> s.contains("HELLO")));
    }

    @Test
    void decodesTrnPayloadText() {
        byte[] afp = sf("D3A090", "TRN DATA".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        AfpInterpretation interpretation = new AfpInterpreter().interpret(afp, "trn.afp");

        assertEquals(1, interpretation.semantics().textObjectCount());
        assertTrue(interpretation.semantics().textFragments().stream().anyMatch(s -> s.contains("TRN")));
    }

    @Test
    void recordsMixedRunCodePageDiagnostics() {
        byte[] mixed = new byte[] {'A', 0x0E, 0x42, 0x43, 0x0F, 'D'};
        byte[] afp = concat(
            sf("D3A8AF", new byte[0]), // BPG
            sf("D3EE9B", ptxCs((byte) 0xDA, mixed)),
            sf("D3A9AF", new byte[0])  // EPG
        );
        AfpInterpretation interpretation = new AfpInterpreter().interpret(afp, "mixed-run.afp");

        assertTrue(interpretation.semantics().mixedRunChunkCount() > 0, "expected mixed-run chunk detection");
        assertTrue(!interpretation.semantics().codePageMappingMatrix().isEmpty(), "expected code page mapping matrix entries");
        assertTrue(interpretation.semantics().codePageResolutionSource().contains("mixed-run"), "expected mixed-run source annotation");
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
