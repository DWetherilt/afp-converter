package com.upland.connect.afp.engine;

import com.upland.connect.afp.api.AfpConverterConfig;
import com.upland.connect.afp.api.ConversionException;
import com.upland.connect.afp.api.ConversionOptions;
import com.upland.connect.afp.api.ConversionRequest;
import com.upland.connect.afp.api.FontPolicy;
import com.upland.connect.afp.api.GeometryPolicy;
import com.upland.connect.afp.api.Limits;
import com.upland.connect.afp.api.MetadataPolicy;
import com.upland.connect.afp.api.ResourceContext;
import com.upland.connect.afp.api.TextFallback;
import com.upland.connect.afp.api.TextPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAfpConverterTest {

    @Test
    void fingerprintDeterminism() {
        ConversionOptions options = new ConversionOptions(
            new TextPolicy(TextFallback.SUBSTITUTE, true),
            new FontPolicy("default"),
            new GeometryPolicy("default"),
            new MetadataPolicy("default"),
            true
        );
        ResourceContext resources = new ResourceContext(java.util.List.of(), Optional.empty(), false);
        Limits limits = new Limits(1000, 10, Duration.ofSeconds(5), 100, 2000);
        EngineIdentity identity = new EngineIdentity("x", "1", "b");

        String f1 = Fingerprints.effectiveFingerprint(identity, "1", options, resources, limits);
        String f2 = Fingerprints.effectiveFingerprint(identity, "1", options, resources, limits);

        assertEquals(f1, f2);
    }

    @Test
    void cacheInvalidatesWhenOptionsChange(@TempDir Path tempDir) throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        FakeAdapter adapter = new FakeAdapter(invocations, false);
        AfpConverterConfig config = newConfig(tempDir, false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DefaultAfpConverter converter = new DefaultAfpConverter(config, executor, adapter);
            converter.convert(request(new TextPolicy(TextFallback.SUBSTITUTE, true)));
            converter.convert(request(new TextPolicy(TextFallback.SUBSTITUTE, true)));
            assertEquals(1, invocations.get(), "second call should be cache hit");

            converter.convert(request(new TextPolicy(TextFallback.FAIL, true)));
            assertEquals(2, invocations.get(), "changed options should miss cache");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cacheInvalidatesWhenInputChanges(@TempDir Path tempDir) throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        FakeAdapter adapter = new FakeAdapter(invocations, false);
        AfpConverterConfig config = newConfig(tempDir, false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DefaultAfpConverter converter = new DefaultAfpConverter(config, executor, adapter);
            converter.convert(request(new TextPolicy(TextFallback.SUBSTITUTE, true), "AFP_A"));
            converter.convert(request(new TextPolicy(TextFallback.SUBSTITUTE, true), "AFP_B"));
            assertEquals(2, invocations.get(), "different input bytes should miss cache");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void tempCleanupBehavior(@TempDir Path tempDir) throws Exception {
        Path tempBase = tempDir.resolve("tmp");
        Files.createDirectories(tempBase);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DefaultAfpConverter successConverter = new DefaultAfpConverter(newConfig(tempDir, false), executor,
                new FakeAdapter(new AtomicInteger(), false));
            successConverter.convert(request(new TextPolicy(TextFallback.SUBSTITUTE, true)));
            assertTrue(countWorkDirs(tempBase) == 0, "successful conversion should cleanup workspace");

            DefaultAfpConverter failKeepConverter = new DefaultAfpConverter(newConfig(tempDir, true), executor,
                new FakeAdapter(new AtomicInteger(), true));
            try {
                failKeepConverter.convert(request(new TextPolicy(TextFallback.FAIL, true)));
            } catch (ConversionException ignored) {
            }
            assertTrue(countWorkDirs(tempBase) > 0, "failed conversion should keep workspace when configured");
        } finally {
            executor.shutdownNow();
        }
    }

    private static long countWorkDirs(Path tempBase) throws IOException {
        Path afpconv = tempBase.resolve("afpconv");
        if (!Files.exists(afpconv)) {
            return 0;
        }
        try (var stream = Files.walk(afpconv)) {
            return stream.filter(Files::isDirectory)
                .filter(path -> !path.equals(afpconv))
                .filter(path -> afpconv.relativize(path).getNameCount() == 2)
                .count();
        }
    }

    private static AfpConverterConfig newConfig(Path base, boolean keepOnFailure) {
        return new AfpConverterConfig(
            new AfpConverterConfig.InProcEngineConfig("fake", Map.of()),
            new AfpConverterConfig.CacheConfig(base.resolve("cache"), 1024 * 1024, Duration.ofDays(1)),
            new AfpConverterConfig.TempConfig(base.resolve("tmp"), keepOnFailure),
            new AfpConverterConfig.LoggingConfig(true, 1024 * 1024)
        );
    }

    private static ConversionRequest request(TextPolicy textPolicy) {
        return request(textPolicy, "AFP");
    }

    private static ConversionRequest request(TextPolicy textPolicy, String afpText) {
        byte[] input = afpText.getBytes(StandardCharsets.UTF_8);
        ConversionOptions options = new ConversionOptions(
            textPolicy,
            new FontPolicy("default"),
            new GeometryPolicy("default"),
            new MetadataPolicy("default"),
            true
        );
        return new ConversionRequest(
            new ByteArrayInputStream(input),
            "source",
            new ResourceContext(java.util.List.of(), Optional.empty(), false),
            options,
            new Limits(10_000, 100, Duration.ofSeconds(30), 1000, 1024 * 1024),
            Optional.empty()
        );
    }

    private static final class FakeAdapter implements AfpEngineAdapter {
        private final AtomicInteger invocations;
        private final boolean fail;
        private final FakeEngineOutputGenerator outputGenerator = new FakeEngineOutputGenerator(new PdfBoxPdfWriter());

        private FakeAdapter(AtomicInteger invocations, boolean fail) {
            this.invocations = invocations;
            this.fail = fail;
        }

        @Override
        public EngineIdentity identity() {
            return new EngineIdentity("fake", "1", "test");
        }

        @Override
        public EngineOutputs convertToWorkspace(EngineInputs inputs, Path workspace) throws ConversionException {
            invocations.incrementAndGet();
            if (fail) {
                throw new ConversionException("ENGINE_FAILED", "intentional");
            }
            try {
                return outputGenerator.writeOutputs(workspace, "test");
            } catch (IOException e) {
                throw new ConversionException("IO_ERROR", "failed to write fake outputs", e);
            }
        }
    }
}
