package solutions.pointzero.symphony.afp.engine;

import java.util.List;

public record AfpInterpretation(String sourceLabel,
                                int inputBytes,
                                int structuredFieldCount,
                                int pageCount,
                                int skippedBytes,
                                List<String> warnings,
                                List<AfpStructuredField> fields,
                                AfpSemantics semantics,
                                byte[] rawAfpBytes) {
    public AfpInterpretation {
        rawAfpBytes = rawAfpBytes == null ? new byte[0] : rawAfpBytes.clone();
    }

    @Override
    public byte[] rawAfpBytes() {
        return rawAfpBytes.clone();
    }
}
