package solutions.pointzero.symphony.afp.api;

import java.util.Objects;

/**
 * Immutable conversion options controlling text/font/geometry/metadata behavior.
 */
public final class ConversionOptions {
    private final TextPolicy textPolicy;
    private final FontPolicy fontPolicy;
    private final GeometryPolicy geometryPolicy;
    private final MetadataPolicy metadataPolicy;
    private final RenderPolicy renderPolicy;
    private final DiagnosticsPolicy diagnosticsPolicy;
    private final boolean deterministic;

    public ConversionOptions(TextPolicy textPolicy,
                             FontPolicy fontPolicy,
                             GeometryPolicy geometryPolicy,
                             MetadataPolicy metadataPolicy,
                             boolean deterministic) {
        this(textPolicy, fontPolicy, geometryPolicy, metadataPolicy, new RenderPolicy("native-fidelity", "strict", "prefer-embedded"),
            new DiagnosticsPolicy("standard"), deterministic);
    }

    public ConversionOptions(TextPolicy textPolicy,
                             FontPolicy fontPolicy,
                             GeometryPolicy geometryPolicy,
                             MetadataPolicy metadataPolicy,
                             RenderPolicy renderPolicy,
                             DiagnosticsPolicy diagnosticsPolicy,
                             boolean deterministic) {
        this.textPolicy = textPolicy == null ? new TextPolicy(TextFallback.SUBSTITUTE, true) : textPolicy;
        this.fontPolicy = fontPolicy == null ? new FontPolicy("default") : fontPolicy;
        this.geometryPolicy = geometryPolicy == null ? new GeometryPolicy("default") : geometryPolicy;
        this.metadataPolicy = metadataPolicy == null ? new MetadataPolicy("default") : metadataPolicy;
        this.renderPolicy = renderPolicy == null ? new RenderPolicy("native-fidelity", "strict", "prefer-embedded") : renderPolicy;
        this.diagnosticsPolicy = diagnosticsPolicy == null ? new DiagnosticsPolicy("standard") : diagnosticsPolicy;
        this.deterministic = deterministic;
    }

    /**
     * @return text conversion policy
     */
    public TextPolicy textPolicy() {
        return textPolicy;
    }

    /**
     * @return font handling policy
     */
    public FontPolicy fontPolicy() {
        return fontPolicy;
    }

    /**
     * @return geometry handling policy
     */
    public GeometryPolicy geometryPolicy() {
        return geometryPolicy;
    }

    /**
     * @return metadata handling policy
     */
    public MetadataPolicy metadataPolicy() {
        return metadataPolicy;
    }

    /**
     * @return render mode/fidelity/resource policy
     */
    public RenderPolicy renderPolicy() {
        return renderPolicy;
    }

    /**
     * @return diagnostics verbosity policy
     */
    public DiagnosticsPolicy diagnosticsPolicy() {
        return diagnosticsPolicy;
    }

    /**
     * @return whether deterministic output mode is enabled
     */
    public boolean deterministic() {
        return deterministic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConversionOptions that)) {
            return false;
        }
        return deterministic == that.deterministic
            && Objects.equals(textPolicy, that.textPolicy)
            && Objects.equals(fontPolicy, that.fontPolicy)
            && Objects.equals(geometryPolicy, that.geometryPolicy)
            && Objects.equals(metadataPolicy, that.metadataPolicy)
            && Objects.equals(renderPolicy, that.renderPolicy)
            && Objects.equals(diagnosticsPolicy, that.diagnosticsPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(textPolicy, fontPolicy, geometryPolicy, metadataPolicy, renderPolicy, diagnosticsPolicy, deterministic);
    }
}
