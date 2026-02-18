package com.upland.connect.afp.api;

import java.time.Duration;

/**
 * Upper bounds used to protect conversion runtime and resources.
 *
 * @param maxInputBytes maximum allowed AFP input bytes
 * @param maxPages maximum output pages
 * @param maxWallTime maximum wall-clock conversion time
 * @param maxImages maximum images allowed during conversion
 * @param maxTempBytes maximum temporary workspace bytes
 */
public record Limits(long maxInputBytes,
                     int maxPages,
                     Duration maxWallTime,
                     int maxImages,
                     long maxTempBytes) {
    public Limits {
        maxWallTime = maxWallTime == null ? Duration.ofSeconds(120) : maxWallTime;
    }
}
