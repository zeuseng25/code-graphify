package com.graphify.settings;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The directories the indexer writes (checkouts, the Maven local repository) must be absolute, creatable and
 * writable on this server. Symlinks are followed (and kept in the stored value): the directory is judged as the
 * indexer will use it. The normalised path is what is checked and saved. A refused save may still have created
 * missing parent directories: they are created before the write probe.
 */
@Component
public class WritableDirectoryValidator implements SettingValidator {

    /** Prefix of the file written and deleted to prove the directory is writable. */
    static final String PROBE_PREFIX = ".graphify-write-check";

    @Override
    public Set<String> keys() {
        return Set.of(SettingKeys.INDEX_WORKSPACE_DIR, SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY);
    }

    /** An absolute path is stored normalised ({@code a/../b} becomes {@code b}); anything else is left for {@link #problem}. */
    @Override
    public String normalize(String key, String value) {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path.normalize().toString() : value;
        } catch (InvalidPathException e) {
            return value;
        }
    }

    @Override
    public Optional<String> problem(String key, String value) {
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            return Optional.of("not a path: " + value);
        }
        if (!path.isAbsolute()) {
            return Optional.of("must be an absolute path: " + value);
        }
        path = path.normalize();
        if (Files.exists(path) && !Files.isDirectory(path)) {
            return Optional.of("not a directory: " + path);
        }
        try {
            Files.createDirectories(path);
            Path probe = Files.createTempFile(path, PROBE_PREFIX, ".tmp");
            Files.delete(probe);
            return Optional.empty();
        } catch (IOException | SecurityException e) {
            return Optional.of("directory is not usable: " + path + " (" + reason(e) + ")");
        }
    }

    private static String reason(Exception e) {
        if (e instanceof FileSystemException fileSystem && fileSystem.getReason() != null) {
            return fileSystem.getReason();
        }
        return e.getClass().getSimpleName();
    }
}
