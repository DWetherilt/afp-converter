package solutions.pointzero.symphony.afp.api;

/**
 * Warning/event severity.
 */
public enum Severity {
    /** Informational message. */
    INFO,
    /** Non-fatal warning. */
    WARN,
    /** Serious issue, potentially recoverable depending on mode. */
    ERROR
}
