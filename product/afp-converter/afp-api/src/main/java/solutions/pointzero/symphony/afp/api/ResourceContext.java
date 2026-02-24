package solutions.pointzero.symphony.afp.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resource lookup context for AFP conversion.
 */
public final class ResourceContext {
    private final List<Path> searchPaths;
    private final Optional<Path> jobResourceRoot;
    private final boolean allowExternalPaths;

    public ResourceContext(List<Path> searchPaths, Optional<Path> jobResourceRoot, boolean allowExternalPaths) {
        this.searchPaths = List.copyOf(searchPaths == null ? List.of() : searchPaths);
        this.jobResourceRoot = jobResourceRoot == null ? Optional.empty() : jobResourceRoot;
        this.allowExternalPaths = allowExternalPaths;
    }

    /**
     * @return ordered resource search paths
     */
    public List<Path> searchPaths() {
        return searchPaths;
    }

    /**
     * @return optional job-scoped resource root
     */
    public Optional<Path> jobResourceRoot() {
        return jobResourceRoot;
    }

    /**
     * @return whether non-whitelisted external paths are permitted
     */
    public boolean allowExternalPaths() {
        return allowExternalPaths;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResourceContext that)) {
            return false;
        }
        return allowExternalPaths == that.allowExternalPaths
            && Objects.equals(searchPaths, that.searchPaths)
            && Objects.equals(jobResourceRoot, that.jobResourceRoot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(searchPaths, jobResourceRoot, allowExternalPaths);
    }
}
