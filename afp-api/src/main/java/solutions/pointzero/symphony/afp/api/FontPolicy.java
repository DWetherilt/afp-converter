package solutions.pointzero.symphony.afp.api;

/**
 * Placeholder font policy.
 *
 * @param mode implementation-defined mode string
 */
public record FontPolicy(String mode) {
    public FontPolicy {
        mode = mode == null ? "default" : mode;
    }
}
