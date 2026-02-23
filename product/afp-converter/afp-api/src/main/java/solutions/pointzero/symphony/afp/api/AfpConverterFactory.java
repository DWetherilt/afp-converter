package solutions.pointzero.symphony.afp.api;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

/**
 * Factory utilities for creating configured converter instances.
 */
public final class AfpConverterFactory {
    private AfpConverterFactory() {
    }

    /**
     * Creates a converter for the supplied configuration and executor.
     *
     * @param config converter configuration
     * @param executor executor used by implementation internals
     * @return configured converter instance
     * @throws ConfigurationException when the implementation cannot be instantiated
     */
    public static AfpConverter create(AfpConverterConfig config, ExecutorService executor)
        throws ConfigurationException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(executor, "executor");

        try {
            Object adapter;
            if (config.engine() instanceof AfpConverterConfig.CliEngineConfig cli) {
                Class<?> cliAdapterClass = Class.forName("solutions.pointzero.symphony.afp.engine.CliAfpEngineAdapter");
                Constructor<?> ctor = cliAdapterClass.getConstructor(AfpConverterConfig.CliEngineConfig.class);
                adapter = ctor.newInstance(cli);
            } else if (config.engine() instanceof AfpConverterConfig.InProcEngineConfig inProc) {
                Class<?> inProcAdapterClass = Class.forName("solutions.pointzero.symphony.afp.engine.InProcAfpEngineAdapter");
                Constructor<?> ctor = inProcAdapterClass.getConstructor(String.class, Map.class);
                adapter = ctor.newInstance(inProc.vendor(), inProc.params());
            } else {
                throw new ConfigurationException("Unsupported engine config");
            }

            Class<?> adapterType = Class.forName("solutions.pointzero.symphony.afp.engine.AfpEngineAdapter");
            Class<?> converterClass = Class.forName("solutions.pointzero.symphony.afp.engine.DefaultAfpConverter");
            Constructor<?> converterCtor = converterClass
                .getConstructor(AfpConverterConfig.class, ExecutorService.class, adapterType);
            return (AfpConverter) converterCtor.newInstance(config, executor, adapter);
        } catch (ClassNotFoundException e) {
            throw new ConfigurationException("afp-engine module is required on classpath", e);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ConfigurationException("Failed to instantiate converter implementation", e);
        }
    }

    /**
     * Loads {@link AfpConverterConfig} from a Java properties file.
     *
     * @param configFile properties file path
     * @return parsed configuration
     * @throws ConfigurationException when parsing or validation fails
     */
    public static AfpConverterConfig load(Path configFile) throws ConfigurationException {
        Properties p = new Properties();
        try (InputStream in = java.nio.file.Files.newInputStream(configFile)) {
            p.load(in);
        } catch (IOException e) {
            throw new ConfigurationException("Unable to load config file: " + configFile, e);
        }

        String engineType = p.getProperty("engine.type", "inproc").trim();
        AfpConverterConfig.EngineConfig engine;
        if ("cli".equalsIgnoreCase(engineType)) {
            String exe = p.getProperty("engine.cli.executable");
            if (exe == null || exe.isBlank()) {
                throw new ConfigurationException("engine.cli.executable is required for cli engine");
            }
            Duration timeout = Duration.ofSeconds(Long.parseLong(p.getProperty("engine.cli.timeoutSeconds", "120")));
            engine = new AfpConverterConfig.CliEngineConfig(Path.of(exe), timeout, Map.of());
        } else if ("inproc".equalsIgnoreCase(engineType)) {
            engine = new AfpConverterConfig.InProcEngineConfig(p.getProperty("engine.inproc.vendor", "pdfbox"), Map.of());
        } else {
            throw new ConfigurationException("Unsupported engine.type: " + engineType);
        }

        Path cacheDir = Path.of(p.getProperty("cache.dir", Path.of(System.getProperty("user.home"), ".cache", "afpconv").toString()));
        long cacheMaxBytes = Long.parseLong(p.getProperty("cache.maxBytes", "1073741824"));
        Duration cacheMaxAge = Duration.ofSeconds(Long.parseLong(p.getProperty("cache.maxAgeSeconds", "604800")));

        Path tempDir = Path.of(p.getProperty("temp.dir", System.getProperty("java.io.tmpdir")));
        boolean keepOnFailure = Boolean.parseBoolean(p.getProperty("temp.keepOnFailure", "false"));

        boolean includeLogs = Boolean.parseBoolean(p.getProperty("logging.includeEngineLogs", "true"));
        int maxLogBytes = Integer.parseInt(p.getProperty("logging.maxEngineLogBytes", "1048576"));

        return new AfpConverterConfig(
            engine,
            new AfpConverterConfig.CacheConfig(cacheDir, cacheMaxBytes, cacheMaxAge),
            new AfpConverterConfig.TempConfig(tempDir, keepOnFailure),
            new AfpConverterConfig.LoggingConfig(includeLogs, maxLogBytes)
        );
    }
}
