package com.graphify.indexer;

import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.IndexWarning;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Indexes one repository checkout: every module's sources, with bindings from its own classpath. */
public class JavaRepositoryIndexer {

    private final JdtParser parser = new JdtParser();

    public IndexResult index(IndexRequest request) {
        Path repoRoot = request.repoRoot().toAbsolutePath().normalize();
        IndexCollector out = new IndexCollector();
        List<Path> sourcepath = request.modules().stream()
                .flatMap(module -> existingRoots(module).stream())
                .distinct()
                .toList();
        Map<ModuleSource, List<Path>> filesByModule = new LinkedHashMap<>();
        for (ModuleSource module : request.modules()) {
            filesByModule.put(module, javaFiles(repoRoot, module, out));
        }
        SourceImports sourceImports = SourceImports.scan(
                filesByModule.values().stream().flatMap(List::stream).distinct().toList());
        SymbolRegistry symbols = new SymbolRegistry(new MethodKeys(sourceImports));
        for (Map.Entry<ModuleSource, List<Path>> entry : filesByModule.entrySet()) {
            ModuleSource module = entry.getKey();
            parser.parse(entry.getValue(), module.classpath(), sourcepath,
                    request.options().parseBatchSize(),
                    (file, unit) -> indexFile(repoRoot, request.options(), module, file, unit, symbols, out),
                    (file, error) -> out.warning(new IndexWarning(module.modulePath(), relativePath(repoRoot, file), 0,
                            "File skipped: JDT failure: " + error)));
        }
        return new IndexResult(symbols.all(), out.declarations(), out.usages(), out.warnings());
    }

    private void indexFile(Path repoRoot, IndexerOptions options, ModuleSource module, Path file,
            CompilationUnit unit, SymbolRegistry symbols, IndexCollector out) {
        String relativePath = relativePath(repoRoot, file);
        try {
            FileContext ctx = new FileContext(module.modulePath(), relativePath, unit, SourceLines.read(file),
                    symbols, new ConfidenceClassifier(unit), new NameOnlyResolver(new ImportResolver(unit)), out,
                    options.snippetMaxLength());
            unit.accept(new DeclarationVisitor(ctx));
            unit.accept(new ReferenceVisitor(ctx));
        } catch (IOException | RuntimeException | StackOverflowError e) {
            // Usages emitted before the failure stay in the result, so the file may be partially indexed.
            out.warning(new IndexWarning(module.modulePath(), relativePath, 0, "File partially indexed or skipped: " + e));
        } finally {
            for (String warning : symbols.drainWarnings()) {
                out.warning(new IndexWarning(module.modulePath(), relativePath, 0, warning));
            }
        }
    }

    private static String relativePath(Path repoRoot, Path file) {
        return repoRoot.relativize(file).toString().replace('\\', '/');
    }

    private static List<Path> existingRoots(ModuleSource module) {
        return module.sourceRoots().stream()
                .map(root -> root.toAbsolutePath().normalize())
                .filter(Files::isDirectory)
                .toList();
    }

    /** A root that cannot be walked is reported and skipped as a whole; the other roots are still indexed. */
    private static List<Path> javaFiles(Path repoRoot, ModuleSource module, IndexCollector out) {
        List<Path> files = new ArrayList<>();
        for (Path root : existingRoots(module)) {
            try (Stream<Path> walk = Files.walk(root)) {
                files.addAll(walk.filter(path -> path.toString().endsWith(".java") && Files.isRegularFile(path))
                        .sorted()
                        .toList());
            } catch (IOException | UncheckedIOException e) {
                out.warning(new IndexWarning(module.modulePath(), relativePath(repoRoot, root), 0,
                        "Source root skipped: " + e));
            }
        }
        return files;
    }
}
