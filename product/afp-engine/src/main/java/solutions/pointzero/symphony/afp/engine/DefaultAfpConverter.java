package solutions.pointzero.symphony.afp.engine;

import solutions.pointzero.symphony.afp.api.AfpConverter;
import solutions.pointzero.symphony.afp.api.AfpConverterConfig;
import solutions.pointzero.symphony.afp.api.CancellationToken;
import solutions.pointzero.symphony.afp.api.ConversionException;
import solutions.pointzero.symphony.afp.api.ConversionRequest;
import solutions.pointzero.symphony.afp.api.ConversionResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public final class DefaultAfpConverter implements AfpConverter {
    private static final String METADATA_SCHEMA_VERSION = "1";

    private final AfpConverterConfig config;
    @SuppressWarnings("unused")
    private final ExecutorService executor;
    private final AfpEngineAdapter adapter;

    public DefaultAfpConverter(AfpConverterConfig config,
                               ExecutorService executor,
                               AfpEngineAdapter adapter) {
        this.config = config;
        this.executor = executor;
        this.adapter = adapter;
    }

    @Override
    public ConversionResult convert(ConversionRequest request) throws ConversionException {
        Instant startedAt = Instant.now();
        byte[] afpBytes = readWithLimit(request.afpStream(), request.limits().maxInputBytes());
        checkCancelled(request.cancel());

        String effectiveFingerprint = Fingerprints.effectiveFingerprint(
            adapter.identity(),
            METADATA_SCHEMA_VERSION,
            request.options(),
            request.resourceContext(),
            request.limits());
        String inputFingerprint = Fingerprints.sha256Hex(afpBytes);
        String fingerprint = Fingerprints.sha256Hex(effectiveFingerprint + ":" + inputFingerprint);

        Path cacheRoot = config.cache().cacheDir().resolve("afpconv");
        Path cacheDir = cacheRoot.resolve(fingerprint.substring(0, 2)).resolve(fingerprint);

        try {
            Files.createDirectories(cacheRoot);
            evictCacheBestEffort(cacheRoot);
            if (Files.isDirectory(cacheDir)) {
                return loadFromCache(cacheDir);
            }
        } catch (IOException e) {
            // continue without cache if cache setup fails
        }

        LocalDate today = LocalDate.now();
        Path workspace = config.temp().baseTempDir()
            .resolve("afpconv")
            .resolve(today.format(DateTimeFormatter.BASIC_ISO_DATE))
            .resolve(UUID.randomUUID().toString());

        boolean success = false;
        try {
            Files.createDirectories(workspace);
            Path inputAfp = workspace.resolve("input.afp");
            Files.write(inputAfp, afpBytes);

            EngineOutputs outputs = adapter.convertToWorkspace(
                new EngineInputs(inputAfp, request.sourceId(), request.resourceContext(), request.options(), request.limits(), request.cancel()),
                workspace);

            validateOutput(outputs.pdfPath(), "output.pdf");
            validateOutput(outputs.metaJsonPath(), "meta.json");
            validateOutput(outputs.diagJsonPath(), "diag.json");
            enforceOutputLimits(request, outputs, workspace, startedAt);

            byte[] pdf = Files.readAllBytes(outputs.pdfPath());
            byte[] meta = Files.readAllBytes(outputs.metaJsonPath());
            byte[] diag = Files.readAllBytes(outputs.diagJsonPath());
            Map<String, String> stats = toStatsMap(outputs.stats());

            writeToCacheAtomically(cacheDir, pdf, meta, diag, stats);
            success = true;
            return new ConversionResult(pdf, meta, diag, stats, List.of());
        } catch (IOException e) {
            throw new ConversionException("IO_ERROR", "I/O error during conversion", e);
        } finally {
            if (success || !config.temp().keepOnFailure()) {
                deleteRecursively(workspace);
            }
        }
    }

    @Override
    public void close() {
        // no-op
    }

    private static void checkCancelled(Optional<CancellationToken> cancel) throws ConversionException {
        if (cancel.isPresent() && cancel.get().isCancelled()) {
            throw new ConversionException("CANCELLED", "Conversion was cancelled");
        }
    }

    private static byte[] readWithLimit(InputStream in, long maxBytes) throws ConversionException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ConversionException("INPUT_TOO_LARGE", "Input exceeds maxInputBytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ConversionException("IO_ERROR", "Failed to read AFP input", e);
        }
    }

    private static void validateOutput(Path path, String expectedName) throws ConversionException {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            throw new ConversionException("ENGINE_OUTPUT_MISSING", "Missing required output: " + expectedName);
        }
    }

    private void enforceOutputLimits(ConversionRequest request,
                                     EngineOutputs outputs,
                                     Path workspace,
                                     Instant startedAt) throws ConversionException {
        if (outputs.stats() != null && request.limits().maxPages() > 0
            && outputs.stats().pageCount() > request.limits().maxPages()) {
            throw new ConversionException("LIMIT_EXCEEDED", "Output exceeds maxPages");
        }

        if (request.limits().maxTempBytes() > 0 && directorySize(workspace) > request.limits().maxTempBytes()) {
            throw new ConversionException("LIMIT_EXCEEDED", "Workspace exceeds maxTempBytes");
        }

        if (request.limits().maxWallTime() != null
            && Duration.between(startedAt, Instant.now()).compareTo(request.limits().maxWallTime()) > 0) {
            throw new ConversionException("TIMEOUT", "Conversion exceeded maxWallTime");
        }
    }

    private ConversionResult loadFromCache(Path cacheDir) throws IOException {
        byte[] pdf = Files.readAllBytes(cacheDir.resolve("output.pdf"));
        byte[] meta = Files.readAllBytes(cacheDir.resolve("meta.json"));
        byte[] diag = Files.readAllBytes(cacheDir.resolve("diag.json"));
        return new ConversionResult(pdf, meta, diag, Map.of("cacheHit", "true"), List.of());
    }

    private void writeToCacheAtomically(Path finalCacheDir,
                                        byte[] pdf,
                                        byte[] meta,
                                        byte[] diag,
                                        Map<String, String> stats) {
        Path parent = finalCacheDir.getParent();
        if (parent == null) {
            return;
        }

        try {
            Files.createDirectories(parent);
            Path staging = parent.resolve(finalCacheDir.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.createDirectories(staging);
            Files.write(staging.resolve("output.pdf"), pdf);
            Files.write(staging.resolve("meta.json"), meta);
            Files.write(staging.resolve("diag.json"), diag);
            Files.writeString(staging.resolve("manifest.json"), manifestJson(stats), StandardCharsets.UTF_8);

            try {
                Files.move(staging, finalCacheDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveEx) {
                if (!Files.exists(finalCacheDir)) {
                    Files.move(staging, finalCacheDir);
                } else {
                    deleteRecursively(staging);
                }
            }
        } catch (IOException e) {
            // best effort: ignore cache write failures
        }
    }

    private String manifestJson(Map<String, String> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"1\",\n");
        sb.append("  \"createdAt\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"stats\": {\n");
        int i = 0;
        for (Map.Entry<String, String> entry : stats.entrySet()) {
            sb.append("    \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append("\"");
            if (++i < stats.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private Map<String, String> toStatsMap(EngineStats stats) {
        Map<String, String> map = new HashMap<>();
        if (stats != null) {
            map.put("pageCount", Integer.toString(stats.pageCount()));
            map.put("substitutedFonts", Integer.toString(stats.substitutedFonts()));
            map.put("missingResources", Integer.toString(stats.missingResources()));
        }
        return map;
    }

    private void evictCacheBestEffort(Path cacheRoot) {
        try {
            if (!Files.exists(cacheRoot)) {
                return;
            }
            List<Path> entries;
            try (var stream = Files.walk(cacheRoot, 2)) {
                entries = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(cacheRoot) && path.getNameCount() - cacheRoot.getNameCount() == 2)
                    .toList();
            }

            Instant now = Instant.now();
            long totalSize = 0;
            for (Path entry : entries) {
                Instant modified = Files.getLastModifiedTime(entry).toInstant();
                if (modified.plus(config.cache().maxAge()).isBefore(now)) {
                    deleteRecursively(entry);
                }
            }

            entries = entries.stream().filter(Files::exists).sorted(Comparator.comparing(this::lastModified)).toList();
            for (Path entry : entries) {
                totalSize += directorySize(entry);
            }

            long target = config.cache().maxBytes();
            if (target <= 0) {
                return;
            }
            for (Path entry : entries) {
                if (totalSize <= target) {
                    break;
                }
                long entrySize = directorySize(entry);
                deleteRecursively(entry);
                totalSize -= entrySize;
            }
        } catch (IOException e) {
            // best effort only
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private long directorySize(Path root) {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // best effort cleanup
        }
    }
}
