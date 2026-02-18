package com.upland.connect.afp.cli;

import com.upland.connect.afp.api.AfpConverter;
import com.upland.connect.afp.api.AfpConverterConfig;
import com.upland.connect.afp.api.AfpConverterFactory;
import com.upland.connect.afp.api.ConversionException;
import com.upland.connect.afp.api.ConversionOptions;
import com.upland.connect.afp.api.ConversionRequest;
import com.upland.connect.afp.api.ConversionResult;
import com.upland.connect.afp.api.DiagnosticsPolicy;
import com.upland.connect.afp.api.FontPolicy;
import com.upland.connect.afp.api.GeometryPolicy;
import com.upland.connect.afp.api.Limits;
import com.upland.connect.afp.api.MetadataPolicy;
import com.upland.connect.afp.api.RenderPolicy;
import com.upland.connect.afp.api.ResourceContext;
import com.upland.connect.afp.api.TextFallback;
import com.upland.connect.afp.api.TextPolicy;
import com.upland.connect.afp.engine.AfpHtmlRenderer;
import com.upland.connect.afp.engine.AfpInterpreter;
import com.upland.connect.afp.engine.AfpInterpretation;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Command(name = "afp2pdf", mixinStandardHelpOptions = true, description = "AFP to text-based PDF + JSON metadata converter")
public final class Afp2PdfMain implements Runnable {
    enum EngineMode {
        INPROC,
        CLI
    }

    @Option(names = "--in", required = true)
    private Path in;

    @Option(names = "--out", required = true)
    private Path out;

    @Option(names = "--meta", required = true)
    private Path meta;

    @Option(names = "--diag", required = true)
    private Path diag;

    @Option(names = "--html")
    private Path html;

    @Option(names = "--resources")
    private List<Path> resources = new ArrayList<>();

    @Option(names = "--textFallback", defaultValue = "SUBSTITUTE")
    private TextFallback textFallback;

    @Option(names = "--metadataMode", defaultValue = "strict", description = "Metadata styling mode: strict|inferred")
    private String metadataMode;

    @Option(names = "--renderMode", defaultValue = "native-fidelity", description = "Rendering mode: native-fidelity|styled-semantic|debug")
    private String renderMode;

    @Option(names = "--fidelityLevel", defaultValue = "strict", description = "Fidelity level: strict|balanced|relaxed")
    private String fidelityLevel;

    @Option(names = "--resourcePolicy", defaultValue = "prefer-embedded", description = "Resource resolution policy: embedded-only|prefer-embedded|external-allowed")
    private String resourcePolicy;

    @Option(names = "--diagVerbosity", defaultValue = "standard", description = "Diagnostics verbosity: minimal|standard|verbose")
    private String diagVerbosity;

    @Option(names = "--cacheDir", defaultValue = "${sys:user.home}/.cache/afpconv")
    private Path cacheDir;

    @Option(names = "--tempDir", defaultValue = "${sys:java.io.tmpdir}")
    private Path tempDir;

    @Option(names = "--engineMode", defaultValue = "INPROC")
    private EngineMode engineMode;

    @Option(names = "--engineExe")
    private Path engineExe;

    @Option(names = "--timeoutSeconds", defaultValue = "120")
    private long timeoutSeconds;

    private int exitCode = 0;

    public static void main(String[] args) {
        Afp2PdfMain app = new Afp2PdfMain();
        int parseCode = new CommandLine(app).execute(args);
        int code = parseCode == 0 ? app.exitCode : 4;
        System.exit(code);
    }

    @Override
    public void run() {
        AfpConverterConfig.EngineConfig engineConfig;
        if (engineMode == EngineMode.CLI) {
            if (engineExe == null) {
                System.err.println("--engineExe is required when --engineMode CLI");
                exitCode = 4;
                return;
            }
            engineConfig = new AfpConverterConfig.CliEngineConfig(engineExe, Duration.ofSeconds(timeoutSeconds), java.util.Map.of());
        } else {
            engineConfig = new AfpConverterConfig.InProcEngineConfig("pdfbox", java.util.Map.of());
        }

        AfpConverterConfig config = new AfpConverterConfig(
            engineConfig,
            new AfpConverterConfig.CacheConfig(cacheDir, 1024L * 1024 * 1024, Duration.ofDays(7)),
            new AfpConverterConfig.TempConfig(tempDir, false),
            new AfpConverterConfig.LoggingConfig(true, 1024 * 1024)
        );

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try (AfpConverter converter = AfpConverterFactory.create(config, executor);
             InputStream input = Files.newInputStream(in)) {

            ConversionRequest request = new ConversionRequest(
                input,
                in.getFileName().toString(),
                new ResourceContext(resources, Optional.empty(), false),
                new ConversionOptions(
                    new TextPolicy(textFallback, true),
                    new FontPolicy("default"),
                    new GeometryPolicy("default"),
                    new MetadataPolicy(metadataMode),
                    new RenderPolicy(renderMode, fidelityLevel, resourcePolicy),
                    new DiagnosticsPolicy(diagVerbosity),
                    true
                ),
                new Limits(64L * 1024 * 1024, 10_000, Duration.ofSeconds(timeoutSeconds), 100_000, 512L * 1024 * 1024),
                Optional.empty()
            );

            ConversionResult result = converter.convert(request);
            writeBytes(out, result.pdfBytes());
            writeBytes(meta, result.metadataJson());
            writeBytes(diag, result.diagnosticsJson());

            if (html != null) {
                AfpInterpretation interpretation = new AfpInterpreter().interpret(in, in.getFileName().toString());
                byte[] htmlBytes = new AfpHtmlRenderer().render(interpretation);
                writeBytes(html, htmlBytes);
            }
            exitCode = 0;
        } catch (ConversionException e) {
            System.err.println("Conversion failed [" + e.code() + "]: " + e.getMessage());
            if ("TIMEOUT".equals(e.code()) || "CANCELLED".equals(e.code())) {
                exitCode = 3;
            } else {
                exitCode = 2;
            }
        } catch (IOException e) {
            System.err.println("I/O failure: " + e.getMessage());
            exitCode = 4;
        } catch (Exception e) {
            System.err.println("Unexpected failure: " + e.getMessage());
            e.printStackTrace(System.err);
            exitCode = 4;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void writeBytes(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, bytes);
    }
}
