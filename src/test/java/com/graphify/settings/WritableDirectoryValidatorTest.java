package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WritableDirectoryValidatorTest {

    private static final String KEY = SettingKeys.INDEX_WORKSPACE_DIR;

    private final WritableDirectoryValidator validator = new WritableDirectoryValidator();

    @TempDir
    Path dir;

    @Test
    void checksBothDirectorySettings() {
        assertThat(validator.keys()).containsExactlyInAnyOrder(SettingKeys.INDEX_WORKSPACE_DIR,
                SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY);
    }

    @Test
    void anExistingWritableDirectoryPassesAndKeepsNoProbe() throws Exception {
        assertThat(validator.problem(KEY, dir.toString())).isEmpty();
        try (var entries = Files.list(dir)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void aMissingDirectoryIsCreated() {
        Path missing = dir.resolve("a/b/repos");

        assertThat(validator.problem(KEY, missing.toString())).isEmpty();
        assertThat(missing).isDirectory();
    }

    @Test
    void aRelativePathIsRefused() {
        assertThat(validator.problem(KEY, "data/repos")).hasValueSatisfying(reason ->
                assertThat(reason).contains("absolute path").contains("data/repos"));
    }

    @Test
    void aFileIsRefused() throws Exception {
        Path file = Files.writeString(dir.resolve("file.txt"), "x");

        assertThat(validator.problem(KEY, file.toString())).hasValueSatisfying(reason ->
                assertThat(reason).contains("not a directory").contains(file.toString()));
    }

    @Test
    void aReadOnlyDirectoryIsRefusedWithThePathAndTheReason() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root can write anywhere");
        Path readOnly = Files.createDirectory(dir.resolve("ro"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(validator.problem(KEY, readOnly.toString())).hasValueSatisfying(reason ->
                    assertThat(reason).startsWith("directory is not usable: " + readOnly).contains("("));
            assertThat(validator.problem(KEY, readOnly.resolve("child").toString())).isPresent();
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aSymlinkToAWritableDirectoryPasses() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path target = Files.createDirectory(dir.resolve("target"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), target);

        assertThat(validator.problem(KEY, link.toString())).isEmpty();
    }
}
