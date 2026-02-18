package com.upland.connect.afp.api;

/**
 * Controls diagnostics verbosity produced by the converter.
 *
 * @param verbosity verbosity profile (for example: minimal, standard, verbose)
 */
public record DiagnosticsPolicy(String verbosity) {
    public DiagnosticsPolicy {
        verbosity = verbosity == null || verbosity.isBlank() ? "standard" : verbosity.trim();
    }
}
