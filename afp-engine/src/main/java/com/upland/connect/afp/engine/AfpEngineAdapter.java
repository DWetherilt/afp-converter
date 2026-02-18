package com.upland.connect.afp.engine;

import com.upland.connect.afp.api.ConversionException;

import java.nio.file.Path;

public interface AfpEngineAdapter {
    EngineIdentity identity();

    EngineOutputs convertToWorkspace(EngineInputs inputs, Path workspace) throws ConversionException;
}
