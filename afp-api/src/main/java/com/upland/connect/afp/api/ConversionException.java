package com.upland.connect.afp.api;

/**
 * Checked exception for conversion failures.
 */
public class ConversionException extends Exception {
    private final String code;

    public ConversionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ConversionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * @return machine-readable error code
     */
    public String code() {
        return code;
    }
}
