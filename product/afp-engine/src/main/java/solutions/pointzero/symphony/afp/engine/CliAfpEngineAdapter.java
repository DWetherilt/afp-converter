package solutions.pointzero.symphony.afp.engine;

import solutions.pointzero.symphony.afp.api.AfpConverterConfig;
import solutions.pointzero.symphony.afp.api.ConversionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class CliAfpEngineAdapter implements AfpEngineAdapter {
    private final Path executable;
    private final Duration timeout;
    private final Map<String, String> env;

    public CliAfpEngineAdapter(AfpConverterConfig.CliEngineConfig config) {
        this.executable = config.executable();
        this.timeout = config.timeout();
        this.env = config.env();
    }

    @Override
    public EngineIdentity identity() {
        return new EngineIdentity("cli-adapter", "poc", executable.getFileName().toString());
    }

    @Override
    public EngineOutputs convertToWorkspace(EngineInputs inputs, Path workspace) throws ConversionException {
        Path outputPdf = workspace.resolve("output.pdf");
        Path metaJson = workspace.resolve("meta.json");
        Path diagJson = workspace.resolve("diag.json");
        Path engineLog = workspace.resolve("engine.log");

        List<String> command = baseCommand(executable.toAbsolutePath());
        command.add("--in");
        command.add(inputs.afpFile().toAbsolutePath().toString());
        command.add("--out");
        command.add(outputPdf.toAbsolutePath().toString());
        command.add("--meta");
        command.add(metaJson.toAbsolutePath().toString());
        command.add("--diag");
        command.add(diagJson.toAbsolutePath().toString());

        for (Path path : inputs.resourceContext().searchPaths()) {
            command.add("--resources");
            command.add(path.toAbsolutePath().toString());
        }
        inputs.resourceContext().jobResourceRoot().ifPresent(path -> {
            command.add("--resources");
            command.add(path.toAbsolutePath().toString());
        });
        command.add("--textFallback");
        command.add(inputs.options().textPolicy().fallback().name());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);
        pb.environment().putAll(env);

        try {
            Process process = pb.start();
            byte[] logs = process.getInputStream().readAllBytes();
            Files.write(engineLog, logs);

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ConversionException("TIMEOUT", "CLI adapter timed out");
            }

            if (process.exitValue() != 0) {
                String logText = new String(logs, java.nio.charset.StandardCharsets.UTF_8);
                throw new ConversionException(
                    "ENGINE_FAILED",
                    "CLI adapter exit code: " + process.exitValue() + "; command=" + String.join(" ", command) + "; logs=" + logText
                );
            }

            return new EngineOutputs(outputPdf, metaJson, diagJson, new EngineStats(0, 0, 0));
        } catch (IOException e) {
            throw new ConversionException("ENGINE_FAILED", "Failed to run CLI engine", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException("CANCELLED", "Interrupted while waiting for CLI engine", e);
        }
    }

    private static List<String> baseCommand(Path executablePath) {
        String exe = executablePath.toString();
        String lower = exe.toLowerCase();
        List<String> command = new ArrayList<>();
        if (lower.endsWith(".ps1")) {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (isWindows) {
                command.add("powershell.exe");
                command.add("-NoProfile");
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-File");
                command.add(exe);
            } else {
                command.add("pwsh");
                command.add("-NoProfile");
                command.add("-File");
                command.add(exe);
            }
            return command;
        }
        command.add(exe);
        return command;
    }
}
