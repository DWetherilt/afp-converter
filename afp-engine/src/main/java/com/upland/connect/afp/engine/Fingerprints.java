package com.upland.connect.afp.engine;

import com.upland.connect.afp.api.ConversionOptions;
import com.upland.connect.afp.api.Limits;
import com.upland.connect.afp.api.ResourceContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

public final class Fingerprints {
    private Fingerprints() {
    }

    public static String optionsFingerprint(ConversionOptions options) {
        TreeMap<String, String> map = new TreeMap<>();
        map.put("deterministic", Boolean.toString(options.deterministic()));
        map.put("text.fallback", options.textPolicy().fallback().name());
        map.put("text.normalizeWhitespace", Boolean.toString(options.textPolicy().normalizeWhitespace()));
        map.put("font.mode", options.fontPolicy().mode());
        map.put("geometry.mode", options.geometryPolicy().mode());
        map.put("metadata.mode", options.metadataPolicy().mode());
        map.put("render.mode", options.renderPolicy().mode());
        map.put("render.fidelityLevel", options.renderPolicy().fidelityLevel());
        map.put("render.resourcePolicy", options.renderPolicy().resourcePolicy());
        map.put("diag.verbosity", options.diagnosticsPolicy().verbosity());
        return sha256Hex(canonicalMap(map));
    }

    public static String effectiveFingerprint(EngineIdentity identity,
                                              String metadataSchemaVersion,
                                              ConversionOptions options,
                                              ResourceContext resources,
                                              Limits limits) {
        TreeMap<String, String> map = new TreeMap<>();
        map.put("engine.name", nullSafe(identity.name()));
        map.put("engine.version", nullSafe(identity.version()));
        map.put("engine.build", nullSafe(identity.build()));
        map.put("metadata.schema", metadataSchemaVersion);
        map.put("options.hash", optionsFingerprint(options));
        map.put("resources.hash", resourceFingerprint(resources));
        map.put("limits.maxInputBytes", Long.toString(limits.maxInputBytes()));
        map.put("limits.maxPages", Integer.toString(limits.maxPages()));
        map.put("limits.maxWallTimeMs", Long.toString(limits.maxWallTime().toMillis()));
        map.put("limits.maxImages", Integer.toString(limits.maxImages()));
        map.put("limits.maxTempBytes", Long.toString(limits.maxTempBytes()));
        return sha256Hex(canonicalMap(map));
    }

    static String resourceFingerprint(ResourceContext resources) {
        TreeMap<String, String> map = new TreeMap<>();
        map.put("resolverMode", resources.allowExternalPaths() ? "allowExternal" : "strictInternal");

        List<String> searchTokens = resources.searchPaths().stream()
            .map(path -> path.getFileName() == null ? "" : path.getFileName().toString())
            .sorted()
            .toList();
        map.put("pathsToken", sha256Hex(String.join("\n", searchTokens)));

        String bundleHash = resources.jobResourceRoot()
            .filter(Files::exists)
            .map(Fingerprints::hashDirectoryContents)
            .orElse("none");
        map.put("bundleContentHash", bundleHash);

        return sha256Hex(canonicalMap(map));
    }

    private static String hashDirectoryContents(Path root) {
        try {
            if (!Files.isDirectory(root)) {
                return sha256Hex("not-a-dir");
            }
            StringBuilder sb = new StringBuilder();
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .forEach(path -> {
                        try {
                            sb.append(root.relativize(path)).append('|')
                                .append(sha256Hex(Files.readAllBytes(path))).append('\n');
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
            return sha256Hex(sb.toString());
        } catch (IOException e) {
            return "error";
        } catch (RuntimeException e) {
            return "error";
        }
    }

    static String canonicalMap(TreeMap<String, String> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(k).append('=').append(v == null ? "" : v).append('\n'));
        return sb.toString();
    }

    static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
