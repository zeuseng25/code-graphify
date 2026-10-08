package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pieces of ClasspathResolver that need no Maven: the child environment and lenient output reading. */
class ClasspathResolverHelpersTest {

    @TempDir
    Path dir;

    @Test
    void mavenInheritsOnlyTheAllowlistedEnvironment() {
        Map<String, String> parent = new HashMap<>();
        parent.put("PATH", "/usr/bin");
        parent.put("HOME", "/home/svc");
        parent.put("JAVA_HOME", "/opt/jdk");
        parent.put("LANG", "en_US.UTF-8");
        parent.put("APP_MASTER_KEY", "master-key-value");
        parent.put("DB_PASSWORD", "db-password-value");
        parent.put("MAVEN_OPTS", "-Dsecret=1");
        parent.put("SENTINEL", "sentinel-value");

        Map<String, String> child = ClasspathResolver.childEnvironment(parent);

        assertThat(child).containsOnly(Map.entry("PATH", "/usr/bin"), Map.entry("HOME", "/home/svc"),
                Map.entry("JAVA_HOME", "/opt/jdk"), Map.entry("LANG", "en_US.UTF-8"));
        assertThat(child.values()).doesNotContain("master-key-value", "db-password-value", "-Dsecret=1",
                "sentinel-value");
    }

    @Test
    void invalidUtf8InMavenOutputIsReadNotThrown() throws Exception {
        Path log = dir.resolve("maven.log");
        byte[] bad = {(byte) 0xC7, (byte) 0xFF, (byte) 0xFE};
        Files.write(log, concat("first\n".getBytes(StandardCharsets.UTF_8), bad,
                "\nhttps://bob:hunter2@repo/x failed\nlast\n".getBytes(StandardCharsets.UTF_8)));

        String tail = ClasspathResolver.tail(ClasspathResolver.readLenient(log), 2);

        assertThat(tail).isEqualTo("https://***@repo/x failed\nlast");
        assertThat(ClasspathResolver.readLenient(log)).startsWith("first\n").contains("�");
    }

    @Test
    void aClasspathFileWithInvalidUtf8StillYieldsItsEntries() throws Exception {
        Path jar = Files.createFile(dir.resolve("lib.jar"));
        Path file = dir.resolve("classpath.txt");
        Files.write(file, concat((jar + File.pathSeparator).getBytes(StandardCharsets.UTF_8),
                dir.resolve("caf").toString().getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xE9}));

        assertThat(ClasspathResolver.entries(ClasspathResolver.readLenient(file))).containsExactly(jar);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] all = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
