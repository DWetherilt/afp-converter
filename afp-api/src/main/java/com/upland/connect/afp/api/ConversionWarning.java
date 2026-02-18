package com.upland.connect.afp.api;

/**
 * Warning emitted during conversion.
 *
 * @param code machine-readable warning code
 * @param message human-readable warning details
 * @param severity warning severity
 */
public record ConversionWarning(String code, String message, Severity severity) {
}
