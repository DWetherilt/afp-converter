package solutions.pointzero.symphony.afp.engine;

import java.util.List;

public record AfpParseResult(List<AfpStructuredField> fields,
                             List<String> warnings,
                             int skippedBytes,
                             boolean truncated) {
}
