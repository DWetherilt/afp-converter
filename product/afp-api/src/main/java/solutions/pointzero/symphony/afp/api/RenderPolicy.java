package solutions.pointzero.symphony.afp.api;

/**
 * Rendering policy controls renderer path and strictness level.
 *
 * @param mode renderer mode (for example: native-fidelity, styled-semantic, debug)
 * @param fidelityLevel fidelity profile (for example: strict, balanced, relaxed)
 * @param resourcePolicy resource resolution policy (for example: embedded-only, prefer-embedded, external-allowed)
 */
public record RenderPolicy(String mode, String fidelityLevel, String resourcePolicy) {
    public RenderPolicy {
        mode = normalize(mode, "native-fidelity");
        fidelityLevel = normalize(fidelityLevel, "strict");
        resourcePolicy = normalize(resourcePolicy, "prefer-embedded");
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
