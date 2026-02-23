package solutions.pointzero.symphony.afp.engine;

import solutions.pointzero.symphony.afp.api.CancellationToken;
import solutions.pointzero.symphony.afp.api.ConversionOptions;
import solutions.pointzero.symphony.afp.api.Limits;
import solutions.pointzero.symphony.afp.api.ResourceContext;

import java.nio.file.Path;
import java.util.Optional;

public record EngineInputs(Path afpFile,
                           String sourceId,
                           ResourceContext resourceContext,
                           ConversionOptions options,
                           Limits limits,
                           Optional<CancellationToken> cancel) {
}
