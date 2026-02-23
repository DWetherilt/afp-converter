package solutions.pointzero.symphony.afp.engine;

import java.nio.file.Path;

public record EngineOutputs(Path pdfPath,
                            Path metaJsonPath,
                            Path diagJsonPath,
                            EngineStats stats) {
}
