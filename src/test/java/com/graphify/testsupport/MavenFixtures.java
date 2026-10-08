package com.graphify.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** A file-based Maven repository and pom snippets for tests that run Maven. */
public final class MavenFixtures {

    private MavenFixtures() {
    }

    /** The fixture groups tests may install into ~/.m2 (shop, acme); nothing else there is touched. */
    public static final List<String> FIXTURE_GROUPS = List.of("com.graphify.testfixture.shop",
            "com.graphify.testfixture.acme");

    /** Removes the fixture groups from a local repository, for tests whose runs install providers into it. */
    public static void deleteFixtureGroups(Path localRepository) throws IOException {
        for (String group : FIXTURE_GROUPS) {
            deleteGroup(localRepository, group);
        }
    }

    static void deleteGroup(Path localRepository, String group) throws IOException {
        Path folder = localRepository.resolve(group.replace('.', '/'));
        if (Files.exists(folder)) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    public static Path fileRepository(Path dir) throws IOException {
        return Files.createDirectories(dir.resolve("maven-repo"));
    }

    public static void publish(Path repo, String groupId, String artifactId, String version, Path jar)
            throws IOException {
        Path folder = repo.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
        Files.createDirectories(folder);
        Files.copy(jar, folder.resolve(artifactId + "-" + version + ".jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(folder.resolve(artifactId + "-" + version + ".pom"),
                pom(groupId, artifactId, version, "", ""));
    }

    public static String pom(String groupId, String artifactId, String version, String parentAndModulesXml,
            String dependenciesXml) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  %s
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(parentAndModulesXml, groupId, artifactId, version, dependenciesXml);
    }

    public static String dependency(String groupId, String artifactId, String version) {
        return "<dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version></dependency>";
    }
}
