package com.graphify.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Writes sources under {@code dir/src}, parses them with bindings and returns units keyed by relative path. */
final class ParsedSources {

    private ParsedSources() {
    }

    static Map<String, CompilationUnit> parse(Path dir, Map<String, String> sources, List<Path> classpath)
            throws IOException {
        Path root = dir.resolve("src").toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file);
        }
        Map<String, CompilationUnit> units = new LinkedHashMap<>();
        new JdtParser().parse(files, classpath, List.of(root), 100,
                (file, unit) -> units.put(root.relativize(file).toString().replace('\\', '/'), unit),
                (file, error) -> {
                    throw new AssertionError("JDT failed on " + file, error);
                });
        return units;
    }

    static <T extends ASTNode> List<T> find(CompilationUnit unit, Class<T> type) {
        List<T> found = new ArrayList<>();
        unit.accept(new ASTVisitor(false) {
            @Override
            public void preVisit(ASTNode node) {
                if (type.isInstance(node)) {
                    found.add(type.cast(node));
                }
            }
        });
        return found;
    }
}
