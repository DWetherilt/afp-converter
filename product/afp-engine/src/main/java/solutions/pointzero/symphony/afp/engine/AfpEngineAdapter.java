package solutions.pointzero.symphony.afp.engine;

import solutions.pointzero.symphony.afp.api.ConversionException;

import java.nio.file.Path;

public interface AfpEngineAdapter {
    EngineIdentity identity();

    EngineOutputs convertToWorkspace(EngineInputs inputs, Path workspace) throws ConversionException;
}
