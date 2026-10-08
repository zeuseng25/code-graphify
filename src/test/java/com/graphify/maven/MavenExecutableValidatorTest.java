package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.graphify.settings.SettingKeys;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenExecutableValidatorTest {

    private static final String KEY = SettingKeys.INDEX_MAVEN_EXECUTABLE;

    @TempDir
    Path dir;

    @BeforeEach
    void posixOnly() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    private Path script(String name, String body) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private MavenExecutableValidator validator(Duration timeout) {
        return new MavenExecutableValidator(() -> timeout);
    }

    @Test
    void checksTheMavenExecutable() {
        assertThat(validator(Duration.ofSeconds(5)).keys()).containsExactly(KEY);
    }

    @Test
    void aProgramThatAnswersVersionPasses() throws Exception {
        Path mvn = script("mvn", "echo 'Apache Maven 3.9.9'");

        assertThat(validator(Duration.ofSeconds(10)).problem(KEY, mvn.toString())).isEmpty();
    }

    @Test
    void runsThePathDirectlyWithVersionFlagAndTheReducedEnvironment() throws Exception {
        // a directory with a space: through a shell this would be two words
        Path mvn = script("with space/mvn", "echo \"arg=$1 key=${APP_MASTER_KEY:-none}\"; exit 3");

        assertThat(validator(Duration.ofSeconds(10)).problem(KEY, mvn.toString())).hasValueSatisfying(reason ->
                assertThat(reason).isEqualTo("exited with 3: arg=-v key=none"));
    }

    @Test
    void reportsTheLastOutputLineOfAFailure() throws Exception {
        Path mvn = script("mvn", "echo first; echo 'The JAVA_HOME environment variable is not defined correctly'; echo; exit 1");

        assertThat(validator(Duration.ofSeconds(10)).problem(KEY, mvn.toString())).hasValue(
                "exited with 1: The JAVA_HOME environment variable is not defined correctly");
    }

    @Test
    void aMissingProgramCannotBeStarted() {
        assertThat(validator(Duration.ofSeconds(5)).problem(KEY, dir.resolve("no-such-mvn").toString()))
                .hasValueSatisfying(reason -> assertThat(reason).startsWith("could not be started: "));
    }

    @Test
    void aHungProgramIsKilledAtTheTimeout() throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Path mvn = script("mvn", "sleep 30 &\necho $! > '" + pidFile + "'\nwait");

        long start = System.nanoTime();
        var problem = validator(Duration.ofMillis(300)).problem(KEY, mvn.toString());
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(problem).hasValue("did not answer -v within PT0.3S");
        assertThat(took).isLessThan(Duration.ofSeconds(10));
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void aTimeoutSettingThatCannotBeReadStartsNothing() throws Exception {
        Path marker = dir.resolve("started");
        Path mvn = script("mvn", "touch '" + marker + "'");
        MavenExecutableValidator broken = new MavenExecutableValidator(() -> {
            throw new IllegalStateException("no timeout");
        });

        assertThatThrownBy(() -> broken.problem(KEY, mvn.toString())).isInstanceOf(IllegalStateException.class);
        assertThat(marker).doesNotExist();
    }

    @Test
    void credentialsInTheOutputAreMasked() throws Exception {
        Path mvn = script("mvn", "echo 'Could not reach https://bob:s3cret@repo.test/maven'; exit 1");

        assertThat(validator(Duration.ofSeconds(10)).problem(KEY, mvn.toString())).hasValueSatisfying(reason ->
                assertThat(reason).doesNotContain("s3cret"));
    }
}
