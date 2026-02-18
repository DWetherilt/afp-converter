package com.upland.connect.afp.api;

/**
 * Fallback strategies when text cannot be preserved as searchable text.
 */
public enum TextFallback {
    /** Fail conversion when text preservation is impossible. */
    FAIL,
    /** Substitute text using fallback behavior/fonts. */
    SUBSTITUTE,
    /** Keep visual output only (outlines/graphics). */
    OUTLINE_ONLY
}
