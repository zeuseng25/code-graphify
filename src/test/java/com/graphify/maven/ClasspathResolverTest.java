package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.TestJars;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.MavenFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs the real `mvn` on the PATH against a file-based repository; plugins come from the developer's ~/.m2. */
class ClasspathResolverTest extends OracleIntegrationTest {

    private static final String GROUP = "com.graphify.testfixture";

    @Autowired
    ClasspathResolver resolver;

    @Autowired
    MavenProjectReader reader;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private SettingsOverride overrides;
    private Path project;

    @BeforeEach
    void setUp() throws Exception {
        overrides = new SettingsOverride(settings);
        jdbc.update("DELETE FROM artifact_repository");
        overrides.set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        Path repo = MavenFixtures.fileRepository(dir);
        Path jar = TestJars.jar(dir, "fixture-lib", Map.of("com/graphify/testfixture/Lib.java",
                "package com.graphify.testfixture; public class Lib { public static int one() { return 1; } }"), Set.of());
        MavenFixtures.publish(repo, GROUP, "fixture-lib", "1.0.0", jar);
        jdbc.update("INSERT INTO artifact_repository (name, url, sort_order) VALUES ('fixture', ?, 0)",
                repo.toUri().toString());

        project = dir.resolve("project");
        String parent = "<parent><groupId>" + GROUP + "</groupId><artifactId>root</artifactId><version>1</version></parent>";
        write("pom.xml", MavenFixtures.pom(GROUP, "root", "1",
                "<packaging>pom</packaging><modules><module>a</module><module>b</module><module>c</module></modules>", ""));
        write("a/pom.xml", MavenFixtures.pom(GROUP, "a", "1", parent, MavenFixtures.dependency(GROUP, "fixture-lib", "1.0.0")));
        write("b/pom.xml", MavenFixtures.pom(GROUP, "b", "1", parent, MavenFixtures.dependency(GROUP, "a", "1")));
        write("c/pom.xml", MavenFixtures.pom(GROUP, "c", "1", parent, MavenFixtures.dependency(GROUP, "missing", "9")));
        write("a/src/main/java/a/A.java", "package a; public class A {}");
        write("b/src/main/java/b/B.java", "package b; public class B { int x = ; }");
        write("c/src/main/java/c/C.java", "package c; public class C {}");
    }

    @AfterEach
    void tearDown() {
        overrides.restore();
        jdbc.update("DELETE FROM artifact_repository");
    }

    private void write(String relative, String content) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private ClasspathResult resolve() {
        return resolver.resolve(project, reader.read(project, List.of("src/main/java")).modules());
    }

    @Test
    void siblingModulesResolveWithoutInstallAndAFailingModuleFailsAlone() {
        ClasspathResult result = resolve();

        assertThat(result.classpaths()).containsKeys("a", "b").doesNotContainKey("c");
        assertThat(result.classpaths().get("a")).anySatisfy(p -> assertThat(p.toString()).endsWith("fixture-lib-1.0.0.jar"));
        assertThat(result.classpaths().get("b")).anySatisfy(p -> assertThat(p.toString()).endsWith("fixture-lib-1.0.0.jar"));
        assertThat(result.error()).isNotNull().contains("missing");
        assertThat(project.resolve("b/target/graphify-classpath.txt")).exists();
    }

    @Test
    void hungMavenIsKilledAtTheTimeout() {
        overrides.set(SettingKeys.INDEX_MAVEN_TIMEOUT, "PT0.2S");

        ClasspathResult result = resolve();

        assertThat(result.classpaths()).isEmpty();
        assertThat(result.error()).contains("timed out");
    }

    @Test
    void aMissingMavenExecutableIsReportedNotThrown() {
        overrides.set(SettingKeys.INDEX_MAVEN_EXECUTABLE, dir.resolve("no-such-mvn").toString());

        ClasspathResult result = resolve();

        assertThat(result.classpaths()).isEmpty();
        assertThat(result.error()).contains("could not start");
    }

    @Test
    void aRepositoryWithoutPomsNeedsNoMaven() throws Exception {
        Path plain = dir.resolve("plain");
        Files.createDirectories(plain.resolve("src"));

        ClasspathResult result = resolver.resolve(plain, reader.read(plain, List.of("src/main/java")).modules());

        assertThat(result.classpaths()).isEmpty();
        assertThat(result.error()).isNull();
    }

    @Test
    void eachNestedProjectResolvesInItsOwnFolderAndAFailingOneFailsAlone() throws Exception {
        Path nested = dir.resolve("nested");
        java.nio.file.Files.createDirectories(nested);
        writeIn(nested, "Lib Project/pom.xml", MavenFixtures.pom(GROUP, "nested-lib", "1", "",
                MavenFixtures.dependency(GROUP, "fixture-lib", "1.0.0")));
        writeIn(nested, "Lib Project/src/main/java/n/L.java", "package n; public class L {}");
        writeIn(nested, "broken/pom.xml", MavenFixtures.pom(GROUP, "nested-broken", "1", "",
                MavenFixtures.dependency(GROUP, "missing", "9")));
        writeIn(nested, "broken/src/main/java/n/B.java", "package n; public class B {}");

        ClasspathResult result = resolver.resolve(nested, reader.read(nested, List.of("src/main/java"), 3).modules());

        assertThat(result.classpaths()).containsOnlyKeys("Lib Project");
        assertThat(result.classpaths().get("Lib Project"))
                .anySatisfy(p -> assertThat(p.toString()).endsWith("fixture-lib-1.0.0.jar"));
        assertThat(result.error()).startsWith("[broken] ").contains("missing");
    }

    private static void writeIn(Path base, String relative, String content) throws Exception {
        Path file = base.resolve(relative);
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, content);
    }

    @Test
    void anInterruptStopsLaunchingMavenForTheRemainingRoots() throws Exception {
        Path nested = dir.resolve("two");
        writeIn(nested, "one/pom.xml", MavenFixtures.pom(GROUP, "one", "1", "", ""));
        writeIn(nested, "two/pom.xml", MavenFixtures.pom(GROUP, "two", "1", "", ""));
        Path counter = dir.resolve("starts.txt");
        Path script = dir.resolve("fake-mvn.sh");
        Files.writeString(script, "#!/bin/sh\necho x >> " + counter + "\nexit 0\n");
        script.toFile().setExecutable(true);
        overrides.set(SettingKeys.INDEX_MAVEN_EXECUTABLE, script.toString());
        List<MavenModule> modules = reader.read(nested, List.of("src/main/java"), 3).modules();

        Thread.currentThread().interrupt();
        ClasspathResult result;
        try {
            result = resolver.resolve(nested, modules);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        long starts = Files.exists(counter) ? Files.readAllLines(counter).size() : 0;
        assertThat(starts).isLessThanOrEqualTo(1);
        assertThat(result.error()).containsOnlyOnce("interrupted");
    }
}
