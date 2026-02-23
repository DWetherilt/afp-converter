package solutions.pointzero.symphony.afp.api;

/**
 * Placeholder metadata policy.
 *
 * @param mode implementation-defined mode string
 */
public record MetadataPolicy(String mode) {
    public MetadataPolicy {
        mode = mode == null ? "default" : mode;
    }
}
