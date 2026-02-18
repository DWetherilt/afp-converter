package com.upland.connect.afp.api;

/**
 * Primary conversion contract for AFP to PDF/metadata/diagnostics.
 */
public interface AfpConverter extends AutoCloseable {
    /**
     * Converts a single AFP request.
     *
     * @param request conversion input and options
     * @return conversion outputs
     * @throws ConversionException when conversion fails or limits are exceeded
     */
    ConversionResult convert(ConversionRequest request) throws ConversionException;

    /**
     * Closes the converter and releases associated resources.
     * <p>
     * Current implementation is a no-op but callers should still close instances.
     */
    @Override
    default void close() {
        // no-op by default
    }
}
