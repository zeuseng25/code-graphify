package com.graphify.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles a small library into a jar so tests can exercise third-party classpath resolution offline. */
public final class TestJars {

    private TestJars() {
    }

    public static Path jar(Path workDir, String name, Map<String, String> sources, Set<String> omittedClassFiles)
            throws IOException {
        Path sourceDir = workDir.resolve(name + "-src");
        Path classesDir = workDir.resolve(name + "-classes");
        Files.createDirectories(classesDir);
        List<String> arguments = new ArrayList<>(List.of("-d", classesDir.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceDir.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac.run(null, null, null, arguments.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("javac failed for test jar " + name);
        }
        Path jar = workDir.resolve(name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> classes = Files.walk(classesDir)) {
            for (Path classFile : classes.filter(Files::isRegularFile).sorted().toList()) {
                String entry = classesDir.relativize(classFile).toString().replace('\\', '/');
                if (omittedClassFiles.contains(entry)) {
                    continue;
                }
                out.putNextEntry(new JarEntry(entry));
                Files.copy(classFile, out);
                out.closeEntry();
            }
        }
        return jar;
    }
}
