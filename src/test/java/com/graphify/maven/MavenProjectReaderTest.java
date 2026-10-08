package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.store.DependencyRecord;
import com.graphify.testsupport.MavenFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenProjectReaderTest {

    private static final List<String> ROOTS = List.of("src/main/java", "src/test/java");

    @TempDir
    Path root;

    private final MavenProjectReader reader = new MavenProjectReader();

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void mkdirs(String relative) throws Exception {
        Files.createDirectories(root.resolve(relative));
    }

    @Test
    void readsTheModuleTreeWithInheritedCoordinatesAndResolvedVersions() throws Exception {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.graphify.testfixture.shop</groupId><artifactId>shop</artifactId><version>2.1.0</version><packaging>pom</packaging>
                  <properties><lib.version>3.4.5</lib.version><revision>2.1.0</revision></properties>
                  <modules><module>core</module><module>app</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.x</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write("core/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.graphify.testfixture.shop</groupId><artifactId>shop</artifactId><version>2.1.0</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                      <version>5.12.0</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """);
        write("app/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.graphify.testfixture.shop</groupId><artifactId>shop</artifactId><version>2.1.0</version></parent>
                  <artifactId>app</artifactId>
                  <build><sourceDirectory>src/java</sourceDirectory></build>
                  <dependencies>
                    <dependency><groupId>com.graphify.testfixture.shop</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>com.x</groupId><artifactId>lib</artifactId></dependency>
                    <dependency><groupId>com.y</groupId><artifactId>unknown</artifactId><version>${no.such}</version></dependency>
                  </dependencies>
                </project>
                """);
        mkdirs("core/src/main/java");
        mkdirs("core/src/test/java");
        mkdirs("app/src/java");

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.warnings()).isEmpty();
        assertThat(project.modules()).extracting(MavenModule::path, MavenModule::groupId, MavenModule::artifactId,
                MavenModule::version).containsExactly(
                tuple(".", "com.graphify.testfixture.shop", "shop", "2.1.0"),
                tuple("core", "com.graphify.testfixture.shop", "core", "2.1.0"),
                tuple("app", "com.graphify.testfixture.shop", "app", "2.1.0"));
        MavenModule core = project.modules().get(1);
        MavenModule app = project.modules().get(2);
        assertThat(core.sourceRoots()).containsExactly(root.resolve("core/src/main/java"), root.resolve("core/src/test/java"));
        assertThat(app.sourceRoots()).containsExactly(root.resolve("app/src/java"));
        assertThat(core.dependencies()).containsExactly(
                new DependencyRecord("org.junit.jupiter", "junit-jupiter", "5.12.0", "test"));
        assertThat(app.dependencies()).containsExactly(
                new DependencyRecord("com.graphify.testfixture.shop", "core", "2.1.0", "compile"),
                new DependencyRecord("com.x", "lib", "3.4.5", "compile"),
                new DependencyRecord("com.y", "unknown", null, "compile"));
        assertThat(project.modules()).allMatch(MavenModule::hasPom);
    }

    @Test
    void aRepositoryWithoutAPomIsOneModuleRootedAtTheCheckout() throws Exception {
        write("src/p/A.java", "package p; class A {}");

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.modules()).singleElement().satisfies(module -> {
            assertThat(module.path()).isEqualTo(".");
            assertThat(module.sourceRoots()).containsExactly(root);
            assertThat(module.hasPom()).isFalse();
        });
    }

    @Test
    void brokenOrMissingModulePomsAreSkippedWithAWarning() throws Exception {
        write("pom.xml", """
                <project><groupId>g</groupId><artifactId>r</artifactId><version>1</version>
                  <modules><module>ok</module><module>broken</module><module>missing</module><module>../outside</module></modules>
                </project>
                """);
        write("ok/pom.xml", "<project><parent><groupId>g</groupId><artifactId>r</artifactId><version>1</version></parent>"
                + "<artifactId>ok</artifactId></project>");
        write("broken/pom.xml", "<project><artifactId>broken");

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.modules()).extracting(MavenModule::path).containsExactly(".", "ok");
        assertThat(project.warnings()).hasSize(3);
    }

    @Test
    void doctypesAreRejectedSoExternalEntitiesCannotBeRead() throws Exception {
        write("pom.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><groupId>&xxe;</groupId><artifactId>r</artifactId><version>1</version></project>
                """);

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.modules()).singleElement().satisfies(m -> assertThat(m.hasPom()).isFalse());
        assertThat(project.warnings()).singleElement().asString().contains("pom.xml");
        assertThat(project.modules().getFirst().groupId()).isNull();
    }

    @Test
    void sourceRootsOutsideTheCheckoutAreIgnored() throws Exception {
        Path sibling = root.getParent().resolve("outside-" + root.getFileName());
        Path absolute = Files.createTempDirectory("outside-abs");
        Files.createDirectories(sibling);
        try {
            write("pom.xml", "<project><groupId>g</groupId><artifactId>r</artifactId><version>1</version>"
                    + "<build><sourceDirectory>../" + sibling.getFileName() + "</sourceDirectory>"
                    + "<testSourceDirectory>" + absolute + "</testSourceDirectory></build></project>");
            mkdirs("src/main/java");

            MavenProject project = reader.read(root, List.of("src/main/java", "../" + sibling.getFileName()));

            assertThat(project.modules().getFirst().sourceRoots()).containsExactly(root.resolve("src/main/java"));
        } finally {
            Files.deleteIfExists(sibling);
            Files.deleteIfExists(absolute);
        }
    }

    @Test
    void patternsAreUsedEvenWhenASourceDirectoryIsDeclared() throws Exception {
        write("pom.xml", "<project><groupId>g</groupId><artifactId>r</artifactId><version>1</version>"
                + "<build><sourceDirectory>src/java</sourceDirectory></build></project>");
        mkdirs("src/java");
        mkdirs("src/test/java");

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.modules().getFirst().sourceRoots())
                .containsExactly(root.resolve("src/java"), root.resolve("src/test/java"));
    }

    @Test
    void readsPackagingTheOutsideParentAndImportedBoms() throws Exception {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.zeus</groupId><artifactId>zeus-bff-parent</artifactId><version>${zeus.version}</version>
                    <relativePath/></parent>
                  <artifactId>app</artifactId><packaging>war</packaging>
                  <properties><zeus.version>2.0.0-SNAPSHOT</zeus.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.zeus</groupId><artifactId>zeus-dependencies</artifactId>
                      <version>${zeus.version}</version><type>pom</type><scope>import</scope></dependency>
                    <dependency><groupId>org.x</groupId><artifactId>managed</artifactId><version>1</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write("lib/pom.xml", MavenFixtures.pom("com.zeus", "lib", "1", "", ""));

        MavenModule app = reader.read(root, List.of("src/main/java")).modules().getFirst();

        assertThat(app.packaging()).isEqualTo("war");
        assertThat(app.parent()).isEqualTo(new Gav("com.zeus", "zeus-bff-parent", "2.0.0-SNAPSHOT"));
        assertThat(app.imports()).containsExactly(new Gav("com.zeus", "zeus-dependencies", "2.0.0-SNAPSHOT"));
        assertThat(app.projectRoot()).isEqualTo(".");
    }

    @Test
    void packagingDefaultsToJar() throws Exception {
        write("pom.xml", MavenFixtures.pom("com.acme", "lib", "1", "", ""));

        assertThat(reader.read(root, List.of("src/main/java")).modules().getFirst().packaging()).isEqualTo("jar");
    }

    @Test
    void withoutARootPomEveryIndependentProjectInSubFoldersIsARoot() throws Exception {
        write("Jwt Demo/pom.xml", MavenFixtures.pom("com.demo", "jwt", "1", "", ""));
        write("Jwt Demo/src/main/java/demo/A.java", "package demo; class A {}");
        write("services/payment/pom.xml", MavenFixtures.pom("com.demo", "payment", "1", "", ""));
        write("services/gateway/pom.xml", MavenFixtures.pom("com.demo", "gateway", "1",
                "<packaging>pom</packaging><modules><module>core</module></modules>", ""));
        write("services/gateway/core/pom.xml", MavenFixtures.pom("com.demo", "gateway-core", "1", "", ""));
        write("node_modules/x/pom.xml", MavenFixtures.pom("x", "x", "1", "", ""));
        write("services/payment/target/pom.xml", MavenFixtures.pom("x", "copied", "1", "", ""));
        write(".hidden/pom.xml", MavenFixtures.pom("x", "hidden", "1", "", ""));
        write("a/b/c/d/pom.xml", MavenFixtures.pom("x", "too-deep", "1", "", ""));

        MavenProject project = reader.read(root, List.of("src/main/java"), 3);

        assertThat(project.roots()).containsExactly("Jwt Demo", "services/gateway", "services/payment");
        assertThat(project.modules()).extracting(MavenModule::path, MavenModule::projectRoot).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Jwt Demo", "Jwt Demo"),
                org.assertj.core.groups.Tuple.tuple("services/gateway", "services/gateway"),
                org.assertj.core.groups.Tuple.tuple("services/gateway/core", "services/gateway"),
                org.assertj.core.groups.Tuple.tuple("services/payment", "services/payment"));
        assertThat(project.modules().getFirst().sourceRoots()).singleElement()
                .satisfies(root -> assertThat(root.toString()).endsWith("Jwt Demo/src/main/java"));
    }

    @Test
    void theDepthLimitIsInclusiveAndTheTwoArgumentReadDoesNotSearch() throws Exception {
        write("a/b/c/pom.xml", MavenFixtures.pom("x", "three", "1", "", ""));

        assertThat(reader.read(root, List.of("src/main/java"), 3).roots()).containsExactly("a/b/c");
        assertThat(reader.read(root, List.of("src/main/java"), 2).roots()).isEmpty();
        assertThat(reader.read(root, List.of("src/main/java")).modules()).singleElement()
                .satisfies(module -> assertThat(module.hasPom()).isFalse());
    }

    @Test
    void symbolicLinksAreNotFollowed() throws Exception {
        write("real/pom.xml", MavenFixtures.pom("x", "real", "1", "", ""));
        java.nio.file.Files.createSymbolicLink(root.resolve("link"), root.resolve("real"));

        assertThat(reader.read(root, List.of("src/main/java"), 3).roots()).containsExactly("real");
    }

    @Test
    void aRootPomStillMeansOneRoot() throws Exception {
        write("pom.xml", MavenFixtures.pom("x", "root", "1", "", ""));
        write("other/pom.xml", MavenFixtures.pom("x", "other", "1", "", ""));

        assertThat(reader.read(root, List.of("src/main/java"), 3).roots()).containsExactly(".");
    }

    @Test
    void javaFilesOutsideTheDiscoveredProjectsAreCountedInAWarning() throws Exception {
        write("app/pom.xml", MavenFixtures.pom("x", "app", "1", "", ""));
        write("app/src/main/java/a/A.java", "package a; class A {}");
        write("loose/B.java", "class B {}");
        write("loose/deep/C.java", "class C {}");
        write("node_modules/D.java", "class D {}");

        assertThat(reader.read(root, List.of("src/main/java"), 3).warnings())
                .contains("2 .java files outside the discovered Maven projects were not indexed");

        Files.delete(root.resolve("loose/B.java"));
        Files.delete(root.resolve("loose/deep/C.java"));
        assertThat(reader.read(root, List.of("src/main/java"), 3).warnings()).isEmpty();
    }
}
