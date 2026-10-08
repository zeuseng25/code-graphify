package com.graphify.indexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * The imports of every repo source file, keyed by the FQN of each top-level type the file declares. Built by a
 * cheap bindings-off pre-pass so a method key can qualify a parameter type that is missing from the classpath
 * the same way no matter which file the method binding was reached from.
 */
final class SourceImports {

    private final Map<String, ImportResolver> byTopLevelType;

    private SourceImports(Map<String, ImportResolver> byTopLevelType) {
        this.byTopLevelType = byTopLevelType;
    }

    static SourceImports empty() {
        return new SourceImports(Map.of());
    }

    /** Files that cannot be read or parsed are left out; the binding pass reports them. */
    static SourceImports scan(List<Path> files) {
        Map<String, ImportResolver> byType = new HashMap<>();
        for (Path file : files) {
            CompilationUnit unit;
            try {
                unit = JdtParser.parseWithoutBindings(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException | StackOverflowError e) {
                continue;
            }
            ImportResolver imports = new ImportResolver(unit);
            String packagePrefix = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName() + ".";
            for (Object type : unit.types()) {
                byType.putIfAbsent(packagePrefix + ((AbstractTypeDeclaration) type).getName().getIdentifier(), imports);
            }
        }
        return new SourceImports(byType);
    }

    Optional<ImportResolver> of(String topLevelTypeFqn) {
        return Optional.ofNullable(byTopLevelType.get(topLevelTypeFqn));
    }
}
