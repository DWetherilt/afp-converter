package solutions.pointzero.symphony.afp.api;

/**
 * Cooperative cancellation signal checked during conversion.
 */
@FunctionalInterface
public interface CancellationToken {
    /**
     * @return {@code true} when conversion should be cancelled
     */
    boolean isCancelled();
}
