package solutions.pointzero.symphony.afp.api;

import java.util.List;
import java.util.Map;

/**
 * Immutable conversion output payload.
 */
public final class ConversionResult {
    private final byte[] pdfBytes;
    private final byte[] metadataJson;
    private final byte[] diagnosticsJson;
    private final Map<String, String> stats;
    private final List<ConversionWarning> warnings;

    public ConversionResult(byte[] pdfBytes,
                            byte[] metadataJson,
                            byte[] diagnosticsJson,
                            Map<String, String> stats,
                            List<ConversionWarning> warnings) {
        this.pdfBytes = pdfBytes == null ? new byte[0] : pdfBytes.clone();
        this.metadataJson = metadataJson == null ? new byte[0] : metadataJson.clone();
        this.diagnosticsJson = diagnosticsJson == null ? new byte[0] : diagnosticsJson.clone();
        this.stats = Map.copyOf(stats == null ? Map.of() : stats);
        this.warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /**
     * @return converted PDF bytes
     */
    public byte[] pdfBytes() {
        return pdfBytes.clone();
    }

    /**
     * @return metadata JSON bytes
     */
    public byte[] metadataJson() {
        return metadataJson.clone();
    }

    /**
     * @return diagnostics JSON bytes
     */
    public byte[] diagnosticsJson() {
        return diagnosticsJson.clone();
    }

    /**
     * @return engine statistics map
     */
    public Map<String, String> stats() {
        return stats;
    }

    /**
     * @return conversion warnings
     */
    public List<ConversionWarning> warnings() {
        return warnings;
    }
}
