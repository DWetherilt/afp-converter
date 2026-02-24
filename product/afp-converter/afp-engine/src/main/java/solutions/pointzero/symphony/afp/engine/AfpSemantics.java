package solutions.pointzero.symphony.afp.engine;

import java.util.List;

public record AfpSemantics(int beginDocumentCount,
                           int endDocumentCount,
                           int beginPageCount,
                           int endPageCount,
                           int textObjectCount,
                           int ptxControlSequenceCount,
                           boolean afplibUsed,
                           int afplibStructuredFieldCount,
                           String resolvedSbcsCharset,
                           String resolvedDbcsCharset,
                           String codePageResolutionSource,
                           List<Integer> codePageHints,
                           List<String> codePageMappingMatrix,
                           int mixedRunChunkCount,
                           int hintedChunkCount,
                           List<String> decodeWarnings,
                           List<String> textFragments) {
}
