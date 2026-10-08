package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsXmlWriterTest {

    @Test
    void writesServersMirrorsAndAnActiveProfileWithEscapedValues() {
        String xml = SettingsXmlWriter.write(List.of(
                new ArtifactRepository(1, "nexus", "https://nexus/repo?a=1&b=2", "ci", "p<w>d", "*"),
                new ArtifactRepository(2, "extra", "file:///repo", null, null, null)), "t0k");

        assertThat(xml).contains("<id>graphify-t0k-1</id>", "<username>ci</username>", "<password>p&lt;w&gt;d</password>",
                "<mirrorOf>*</mirrorOf>", "<url>https://nexus/repo?a=1&amp;b=2</url>",
                "<activeProfile>graphify</activeProfile>", "<url>file:///repo</url>");
        assertThat(xml).doesNotContain("<id>graphify-t0k-2</id><username>");
        assertThat(xml.indexOf("<id>graphify-t0k-2</id>")).isGreaterThan(xml.indexOf("<profile>"));
        assertThat(xml).contains("<server><id>graphify-t0k-1</id>", "<mirror><id>graphify-t0k-1</id>");
    }

    @Test
    void artifactRepositoryToStringOmitsTheSecret() {
        assertThat(new ArtifactRepository(1, "n", "u", "user", "S3CR3T", null).toString()).doesNotContain("S3CR3T");
        assertThat(new ArtifactRepository(1, "n", "https://bob:hunter2@repo/m2", null, null, null).toString())
                .doesNotContain("hunter2").contains("https://***@repo/m2");
    }
}
