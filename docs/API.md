# AFP Converter API

Reference for the public Java API in module `afp-api` (`solutions.pointzero.symphony.afp.api`).

## Module and Package

- Gradle module: `afp-api`
- Java package: `solutions.pointzero.symphony.afp.api`

## Core Entry Points

### `AfpConverter`

```java
public interface AfpConverter extends AutoCloseable {
    ConversionResult convert(ConversionRequest request) throws ConversionException;
}
```

- Performs one AFP conversion request.
- `close()` is currently a no-op but keep try-with-resources for future compatibility.

### `AfpConverterFactory`

```java
AfpConverter create(AfpConverterConfig config, ExecutorService executor)
AfpConverterConfig load(Path configFile)
```

- `create(...)` instantiates a converter from config.
- `load(...)` reads a Java properties config file.
- `create(...)` requires `afp-engine` on classpath (implementation is loaded reflectively).

## Minimal Usage

```java
import solutions.pointzero.symphony.afp.api.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

Path in = Path.of("input.afp");

AfpConverterConfig config = new AfpConverterConfig(
    new AfpConverterConfig.InProcEngineConfig("pdfbox", Map.of()),
    new AfpConverterConfig.CacheConfig(Path.of(".cache/afpconv"), 1_073_741_824L, Duration.ofDays(7)),
    new AfpConverterConfig.TempConfig(Path.of(System.getProperty("java.io.tmpdir")), false),
    new AfpConverterConfig.LoggingConfig(true, 1_048_576)
);

ExecutorService executor = Executors.newFixedThreadPool(1);
try (AfpConverter converter = AfpConverterFactory.create(config, executor);
     InputStream afp = Files.newInputStream(in)) {

    ConversionRequest request = new ConversionRequest(
        afp,
        in.getFileName().toString(),
        new ResourceContext(java.util.List.of(), Optional.empty(), false),
        new ConversionOptions(
            new TextPolicy(TextFallback.SUBSTITUTE, true),
            new FontPolicy("default"),
            new GeometryPolicy("default"),
            new MetadataPolicy("inferred"),
            new RenderPolicy("native-fidelity", "strict", "prefer-embedded"),
            new DiagnosticsPolicy("standard"),
            true
        ),
        new Limits(64L * 1024 * 1024, 10_000, Duration.ofSeconds(120), 100_000, 512L * 1024 * 1024),
        Optional.empty()
    );

    ConversionResult result = converter.convert(request);
    Files.write(Path.of("output.pdf"), result.pdfBytes());
    Files.write(Path.of("meta.json"), result.metadataJson());
    Files.write(Path.of("diag.json"), result.diagnosticsJson());
} finally {
    executor.shutdownNow();
}
```

## Request Model

### `ConversionRequest`

Fields:
- `InputStream afpStream` (required)
- `String sourceId`
- `ResourceContext resourceContext`
- `ConversionOptions options`
- `Limits limits`
- `Optional<CancellationToken> cancel`

Constructor defaults when `null` is passed:
- `sourceId = "unknown"`
- `resourceContext = new ResourceContext(List.of(), Optional.empty(), false)`
- `options = new ConversionOptions(... deterministic=true)`
- `limits = new Limits(32MB, 10000 pages, 120s, 100000 images, 512MB temp)`
- `cancel = Optional.empty()`

### `ResourceContext`

- `List<Path> searchPaths`
- `Optional<Path> jobResourceRoot`
- `boolean allowExternalPaths`

### `ConversionOptions`

- `TextPolicy textPolicy`
- `FontPolicy fontPolicy`
- `GeometryPolicy geometryPolicy`
- `MetadataPolicy metadataPolicy`
- `RenderPolicy renderPolicy`
- `DiagnosticsPolicy diagnosticsPolicy`
- `boolean deterministic`

Supporting types:
- `TextFallback`: `FAIL | SUBSTITUTE | OUTLINE_ONLY`
- `TextPolicy(TextFallback fallback, boolean normalizeWhitespace)`
- `FontPolicy(String mode)` placeholder
- `GeometryPolicy(String mode)` placeholder
- `MetadataPolicy(String mode)` placeholder
  - supported in-process metadata style modes:
    - `"strict"`: strict style metadata without inferred ratios/weights (default)
    - `"inferred"`: inferred region/text style hints
- `RenderPolicy(String mode, String fidelityLevel, String resourcePolicy)`
  - commonly used values:
    - `mode`: `"native-fidelity"` (default), `"styled-semantic"`, `"debug"`
    - `fidelityLevel`: `"strict"` (default), `"balanced"`, `"relaxed"`
    - `resourcePolicy`: `"prefer-embedded"` (default), `"embedded-only"`, `"external-allowed"`
- `DiagnosticsPolicy(String verbosity)`
  - commonly used values: `"standard"` (default), `"minimal"`, `"verbose"`

Defaults:
- `TextPolicy(fallback=SUBSTITUTE, normalizeWhitespace=true)`
- `FontPolicy("default")`
- `GeometryPolicy("default")`
- `MetadataPolicy("default")` (treated as `"strict"` by current in-process engine)
- `RenderPolicy("native-fidelity", "strict", "prefer-embedded")`
- `DiagnosticsPolicy("standard")`

### `Limits`

```java
Limits(long maxInputBytes,
       int maxPages,
       Duration maxWallTime,
       int maxImages,
       long maxTempBytes)
```

- If `maxWallTime` is null, defaults to `Duration.ofSeconds(120)`.

## Result Model

### `ConversionResult`

- `byte[] pdfBytes`
- `byte[] metadataJson`
- `byte[] diagnosticsJson`
- `Map<String, String> stats`
- `List<ConversionWarning> warnings`

Arrays are defensively copied in/out.

### `ConversionWarning`

```java
ConversionWarning(String code, String message, Severity severity)
```

`Severity`:
- `INFO`
- `WARN`
- `ERROR`

## Errors and Cancellation

### `ConversionException`

- Checked exception for conversion failures.
- Includes a machine-readable `code()` string.

Common codes emitted by current implementation include:
- `INPUT_TOO_LARGE`
- `IO_ERROR`
- `ENGINE_FAILED`
- `ENGINE_OUTPUT_MISSING`
- `LIMIT_EXCEEDED`
- `TIMEOUT`
- `CANCELLED`

Additional adapter-specific codes may be emitted by custom backends.

### `ConfigurationException`

- Checked exception for invalid or load/instantiation config issues.

### `CancellationToken`

```java
@FunctionalInterface
interface CancellationToken {
    boolean isCancelled();
}
```

Pass in `ConversionRequest.cancel()` to support cooperative cancellation.

## PM Tooling Commands

`pm-tools` exposes SQL-backed governance commands used by root Gradle tasks:

- `lint-policy-rules --db <path> --output <path>`
  - Validates required enabled policy rules in `policy_rule_catalog`.
  - Default required set currently includes `PM-COMMS-001`.
- `lint-data-dictionary --db <path> --output <path>`
  - Validates required object definitions in `pm_data_dictionary`.

## Engine Configuration (`AfpConverterConfig`)

### `EngineConfig` (sealed)

- `InProcEngineConfig(String vendor, Map<String, String> params)`
- `CliEngineConfig(Path executable, Duration timeout, Map<String, String> env)`

Defaults:
- In-proc vendor defaults to `"unknown"` if null.
- CLI timeout defaults to `120s` if null.

### Cache / Temp / Logging

- `CacheConfig(Path cacheDir, long maxBytes, Duration maxAge)`  
  `maxAge` defaults to `7 days` if null.
- `TempConfig(Path baseTempDir, boolean keepOnFailure)`
- `LoggingConfig(boolean includeEngineLogs, int maxEngineLogBytes)`

## Properties Config Loading

`AfpConverterFactory.load(Path)` supports:

- `engine.type` = `inproc` (default) or `cli`
- CLI:
  - `engine.cli.executable` (required when `engine.type=cli`)
  - `engine.cli.timeoutSeconds` (default `120`)
- In-proc:
  - `engine.inproc.vendor` (default `pdfbox`)
- Cache:
  - `cache.dir` (default `${user.home}/.cache/afpconv`)
  - `cache.maxBytes` (default `1073741824`)
  - `cache.maxAgeSeconds` (default `604800`)
- Temp:
  - `temp.dir` (default `${java.io.tmpdir}`)
  - `temp.keepOnFailure` (default `false`)
- Logging:
  - `logging.includeEngineLogs` (default `true`)
  - `logging.maxEngineLogBytes` (default `1048576`)

## Concurrency and Lifecycle Notes

- `AfpConverter` instances are intended to be reused.
- Provide an `ExecutorService` sized for your host runtime.
- Always close converter and shutdown executor cleanly.
