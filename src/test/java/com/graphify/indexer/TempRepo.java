package com.graphify.indexer;

import com.graphify.indexer.model.IndexResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a multi-module repository on disk ({@code <module>/src/main/java/...}) and indexes it. */
final class TempRepo {

    private final Path root;
    private final Map<String, List<Path>> classpaths = new LinkedHashMap<>();

    private TempRepo(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    static TempRepo at(Path root) {
        return new TempRepo(root);
    }

    TempRepo java(String module, String sourcePath, String content) throws IOException {
        return rawJava(module, sourcePath, content.getBytes(StandardCharsets.UTF_8));
    }

    TempRepo rawJava(String module, String sourcePath, byte[] content) throws IOException {
        Path file = sourceRoot(module).resolve(sourcePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        classpaths.computeIfAbsent(module, m -> new ArrayList<>());
        return this;
    }

    TempRepo classpath(String module, Path jar) {
        classpaths.computeIfAbsent(module, m -> new ArrayList<>()).add(jar);
        return this;
    }

    IndexRequest request(int parseBatchSize) {
        List<ModuleSource> modules = classpaths.entrySet().stream()
                .map(e -> new ModuleSource(e.getKey(), List.of(sourceRoot(e.getKey())), e.getValue()))
                .toList();
        return new IndexRequest(root, modules, new IndexerOptions(parseBatchSize, 200));
    }

    IndexResult index() {
        return index(50);
    }

    IndexResult index(int parseBatchSize) {
        return new JavaRepositoryIndexer().index(request(parseBatchSize));
    }

    private Path sourceRoot(String module) {
        return root.resolve(module).resolve("src/main/java");
    }
}
