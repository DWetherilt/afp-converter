package com.upland.connect.afp.engine;

public record EngineStats(int pageCount,
                          int substitutedFonts,
                          int missingResources) {
}
