package com.upland.connect.afp.engine;

import java.util.List;

public record AfpParseResult(List<AfpStructuredField> fields,
                             List<String> warnings,
                             int skippedBytes,
                             boolean truncated) {
}
