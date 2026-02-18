package com.upland.connect.afp.engine;

import java.nio.file.Path;

public record EngineOutputs(Path pdfPath,
                            Path metaJsonPath,
                            Path diagJsonPath,
                            EngineStats stats) {
}
