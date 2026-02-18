package com.upland.connect.afp.engine;

import com.upland.connect.afp.api.CancellationToken;
import com.upland.connect.afp.api.ConversionOptions;
import com.upland.connect.afp.api.Limits;
import com.upland.connect.afp.api.ResourceContext;

import java.nio.file.Path;
import java.util.Optional;

public record EngineInputs(Path afpFile,
                           String sourceId,
                           ResourceContext resourceContext,
                           ConversionOptions options,
                           Limits limits,
                           Optional<CancellationToken> cancel) {
}
