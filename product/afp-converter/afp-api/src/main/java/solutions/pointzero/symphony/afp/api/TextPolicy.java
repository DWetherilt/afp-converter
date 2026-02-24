package solutions.pointzero.symphony.afp.api;

/**
 * Text extraction/rendering behavior.
 *
 * @param fallback policy when text cannot be represented directly
 * @param normalizeWhitespace whether whitespace should be normalized
 */
public record TextPolicy(TextFallback fallback, boolean normalizeWhitespace) {
    public TextPolicy {
        fallback = fallback == null ? TextFallback.SUBSTITUTE : fallback;
    }
}
