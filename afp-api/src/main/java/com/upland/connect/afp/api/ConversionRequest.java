package com.upland.connect.afp.api;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable conversion request payload.
 */
public final class ConversionRequest {
    private final InputStream afpStream;
    private final String sourceId;
    private final ResourceContext resourceContext;
    private final ConversionOptions options;
    private final Limits limits;
    private final Optional<CancellationToken> cancel;

    public ConversionRequest(InputStream afpStream,
                             String sourceId,
                             ResourceContext resourceContext,
                             ConversionOptions options,
                             Limits limits,
                             Optional<CancellationToken> cancel) {
        this.afpStream = Objects.requireNonNull(afpStream, "afpStream");
        this.sourceId = sourceId == null ? "unknown" : sourceId;
        this.resourceContext = resourceContext == null ? new ResourceContext(null, Optional.empty(), false) : resourceContext;
        this.options = options == null
            ? new ConversionOptions(null, null, null, null, true)
            : options;
        this.limits = limits == null
            ? new Limits(32L * 1024 * 1024, 10_000, java.time.Duration.ofSeconds(120), 100_000, 512L * 1024 * 1024)
            : limits;
        this.cancel = cancel == null ? Optional.empty() : cancel;
    }

    /**
     * @return AFP input stream to convert
     */
    public InputStream afpStream() {
        return afpStream;
    }

    /**
     * @return logical source identifier used for diagnostics/tracing
     */
    public String sourceId() {
        return sourceId;
    }

    /**
     * @return resource lookup settings
     */
    public ResourceContext resourceContext() {
        return resourceContext;
    }

    /**
     * @return conversion behavior options
     */
    public ConversionOptions options() {
        return options;
    }

    /**
     * @return input/output safety limits
     */
    public Limits limits() {
        return limits;
    }

    /**
     * @return optional cancellation token
     */
    public Optional<CancellationToken> cancel() {
        return cancel;
    }
}
