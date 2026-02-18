package com.upland.connect.afp.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Top-level converter configuration.
 *
 * @param engine engine mode and runtime settings
 * @param cache cache configuration
 * @param temp temporary workspace configuration
 * @param logging logging configuration
 */
public record AfpConverterConfig(EngineConfig engine,
                                 CacheConfig cache,
                                 TempConfig temp,
                                 LoggingConfig logging) {

    /**
     * Marker interface for engine runtime configuration.
     */
    public sealed interface EngineConfig permits InProcEngineConfig, CliEngineConfig {
    }

    /**
     * In-process engine configuration.
     *
     * @param vendor logical vendor/implementation label
     * @param params optional engine parameters
     */
    public record InProcEngineConfig(String vendor, Map<String, String> params) implements EngineConfig {
        public InProcEngineConfig {
            params = Map.copyOf(params == null ? Map.of() : params);
            vendor = vendor == null ? "unknown" : vendor;
        }
    }

    /**
     * External CLI engine configuration.
     *
     * @param executable executable or script path
     * @param timeout per-conversion timeout
     * @param env additional environment variables
     */
    public record CliEngineConfig(Path executable,
                                  Duration timeout,
                                  Map<String, String> env) implements EngineConfig {
        public CliEngineConfig {
            timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
            env = Map.copyOf(env == null ? Map.of() : env);
        }
    }

    /**
     * Cache sizing and retention policy.
     *
     * @param cacheDir cache root directory
     * @param maxBytes target maximum cache size
     * @param maxAge maximum age before eviction
     */
    public record CacheConfig(Path cacheDir, long maxBytes, Duration maxAge) {
        public CacheConfig {
            maxAge = maxAge == null ? Duration.ofDays(7) : maxAge;
        }
    }

    /**
     * Temporary workspace settings.
     *
     * @param baseTempDir workspace root
     * @param keepOnFailure whether failed workspaces should be retained
     */
    public record TempConfig(Path baseTempDir, boolean keepOnFailure) {
    }

    /**
     * Logging capture settings.
     *
     * @param includeEngineLogs whether backend logs should be captured
     * @param maxEngineLogBytes maximum bytes retained for engine logs
     */
    public record LoggingConfig(boolean includeEngineLogs, int maxEngineLogBytes) {
    }
}
