package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.SettingsOverride;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs the real `mvn install` into ~/.m2/repository under a test-only group that each test removes. */
class ArtifactInstallerTest extends OracleIntegrationTest {

    static final String GROUP = "com.graphify.testfixture.acme";
    static final Path LOCAL = Path.of(System.getProperty("user.home"), ".m2", "repository");
    static final Path GROUP_FOLDER = LOCAL.resolve(GROUP.replace('.', '/'));

    @Autowired
    ArtifactInstaller installer;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private SettingsOverride overrides;

    @BeforeEach
    void setUp() throws Exception {
        overrides = new SettingsOverride(settings).set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, LOCAL.toString());
        jdbc.update("DELETE FROM artifact_repository");
        deleteGroup();
    }

    @AfterEach
    void tearDown() throws Exception {
        overrides.restore();
        deleteGroup();
    }

    static void deleteGroup() throws Exception {
        if (Files.exists(GROUP_FOLDER)) {
            try (Stream<Path> files = Files.walk(GROUP_FOLDER)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private void write(String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static String pom(String artifactId, String packaging, String modulesXml) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>1.0-SNAPSHOT</version>
                  <packaging>%s</packaging>%s
                  <properties><maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                </project>
                """.formatted(GROUP, artifactId, packaging, modulesXml);
    }

    private void touch(String artifactId, String... extensions) throws Exception {
        Path folder = GROUP_FOLDER.resolve(artifactId).resolve("1.0-SNAPSHOT");
        Files.createDirectories(folder);
        for (String extension : extensions) {
            Files.writeString(folder.resolve(artifactId + "-1.0-SNAPSHOT." + extension), "x");
        }
    }

    @Test
    void installsTheRequestedRootAndReportsWhatIsPresent() throws Exception {
        write("lib/pom.xml", pom("lib", "jar", ""));
        write("lib/src/main/java/acme/Lib.java", "package acme; public class Lib {}");
        write("other/pom.xml", pom("other", "jar", ""));
        write("other/src/main/java/acme/Other.java", "package acme; public class Other {}");
        Gav lib = new Gav(GROUP, "lib", "1.0-SNAPSHOT");
        Gav other = new Gav(GROUP, "other", "1.0-SNAPSHOT");

        assertThat(installer.present(lib, "jar")).isFalse();
        assertThat(installer.install(dir, List.of("lib"))).isNull();

        assertThat(installer.present(lib, "jar")).isTrue();
        assertThat(installer.present(other, "jar")).isFalse();
    }

    @Test
    void aPomOnlyProviderNeedsOnlyItsPom() throws Exception {
        write("pom.xml", pom("parent", "pom", ""));

        assertThat(installer.install(dir, List.of("."))).isNull();
        assertThat(installer.present(new Gav(GROUP, "parent", "1.0-SNAPSHOT"), "pom")).isTrue();
        assertThat(installer.present(new Gav(GROUP, "parent", "1.0-SNAPSHOT"), "jar")).isFalse();
    }

    @Test
    void aProjectThatDoesNotCompileReturnsTheMavenOutput() throws Exception {
        write("pom.xml", pom("broken", "jar", ""));
        write("src/main/java/acme/Broken.java", "package acme; public class Broken { int x = ; }");

        String error = installer.install(dir, List.of("."));

        assertThat(error).startsWith("Maven exited with").contains("COMPILATION ERROR");
        assertThat(installer.present(new Gav(GROUP, "broken", "1.0-SNAPSHOT"), "jar")).isFalse();
    }

    @Test
    void rootsInstallInTheGivenOrderAndTheFirstFailureStopsTheRest() throws Exception {
        write("b-first/pom.xml", pom("bfirst", "jar", ""));
        write("b-first/src/main/java/acme/B.java", "package acme; public class B {}");
        write("a-broken/pom.xml", pom("abroken", "jar", ""));
        write("a-broken/src/main/java/acme/A.java", "package acme; public class A { int x = ; }");
        write("c-last/pom.xml", pom("clast", "jar", ""));
        write("c-last/src/main/java/acme/C.java", "package acme; public class C {}");

        String error = installer.install(dir, List.of("b-first", "a-broken", "c-last"));

        assertThat(error).startsWith("[a-broken] Maven exited with");
        assertThat(installer.present(new Gav(GROUP, "bfirst", "1.0-SNAPSHOT"), "jar")).isTrue();
        assertThat(installer.present(new Gav(GROUP, "clast", "1.0-SNAPSHOT"), "jar")).isFalse();
    }

    @Test
    void anInterruptedThreadLaunchesNoMaven() throws Exception {
        write("pom.xml", pom("never", "jar", ""));
        Thread.currentThread().interrupt();
        try {
            assertThat(installer.install(dir, List.of("."))).isEqualTo("Maven install was interrupted");
        } finally {
            Thread.interrupted();
        }
        assertThat(installer.present(new Gav(GROUP, "never", "1.0-SNAPSHOT"), "pom")).isFalse();
    }

    @Test
    void presenceDependsOnThePackaging() throws Exception {
        Gav gav = new Gav(GROUP, "pk", "1.0-SNAPSHOT");
        assertThat(installer.present(gav, "pom")).isFalse();

        touch("pk", "pom");
        assertThat(installer.present(gav, "pom")).isTrue();
        assertThat(installer.present(gav, null)).isTrue();
        assertThat(installer.present(gav, "${packaging.type}")).isTrue();
        assertThat(installer.present(gav, "jar")).isFalse();
        assertThat(installer.present(gav, "war")).isFalse();
        assertThat(installer.present(gav, "ejb")).isFalse();

        touch("pk", "jar");
        for (String packaging : List.of("jar", "bundle", "maven-plugin", "ejb")) {
            assertThat(installer.present(gav, packaging)).as(packaging).isTrue();
        }
        assertThat(installer.present(gav, "war")).isFalse();

        touch("pk", "war");
        assertThat(installer.present(gav, "war")).isTrue();
    }

    @Test
    void coordinatesThatEscapeTheLocalRepositoryAreNeverPresent() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("repo"));
        new SettingsOverride(settings).set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, repo.toString());
        try {
            assertThat(installer.present(new Gav(".etc", "../x", "1/../../y"), "pom")).isFalse();
            assertThat(installer.present(new Gav("a..b", "x", "1"), "pom")).isFalse();
            assertThat(installer.present(new Gav("a", "..", "1"), "pom")).isFalse();
            assertThat(installer.present(new Gav("a", "x\\y", "1"), "pom")).isFalse();
            try (Stream<Path> files = Files.list(repo)) {
                assertThat(files.toList()).isEmpty();
            }
        } finally {
            overrides.set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, LOCAL.toString());
        }
    }
}
