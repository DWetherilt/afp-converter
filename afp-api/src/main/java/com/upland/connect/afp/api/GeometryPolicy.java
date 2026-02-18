package com.upland.connect.afp.api;

/**
 * Placeholder geometry policy.
 *
 * @param mode implementation-defined mode string
 */
public record GeometryPolicy(String mode) {
    public GeometryPolicy {
        mode = mode == null ? "default" : mode;
    }
}
