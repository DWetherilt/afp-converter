package solutions.pointzero.symphony.afp.engine;

import solutions.pointzero.symphony.afp.api.ConversionException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public final class InProcAfpEngineAdapter implements AfpEngineAdapter {
    private final String vendor;
    private final Map<String, String> params;
    private final FakeEngineOutputGenerator outputGenerator;
    private final AfpInterpreter interpreter;

    public InProcAfpEngineAdapter(String vendor, Map<String, String> params) {
        this.vendor = vendor;
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.outputGenerator = new FakeEngineOutputGenerator(new PdfBoxPdfWriter());
        this.interpreter = new AfpInterpreter();
    }

    @Override
    public EngineIdentity identity() {
        return new EngineIdentity("inproc-adapter", "poc", vendor + ":" + params.size());
    }

    @Override
    public EngineOutputs convertToWorkspace(EngineInputs inputs, Path workspace) throws ConversionException {
        try {
            AfpInterpretation interpretation = interpreter.interpret(inputs.afpFile(), inputs.sourceId());
            String metadataMode = inputs.options() == null || inputs.options().metadataPolicy() == null
                ? "default"
                : inputs.options().metadataPolicy().mode();
            return outputGenerator.writeOutputs(
                workspace,
                interpretation,
                metadataMode,
                inputs.options(),
                inputs.resourceContext()
            );
        } catch (IOException e) {
            throw new ConversionException("ENGINE_FAILED", "Failed to generate in-process outputs", e);
        }
    }
}
