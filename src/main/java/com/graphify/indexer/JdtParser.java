package com.graphify.indexer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;

/** Parses Java files with Eclipse JDT, resolving bindings against a classpath and sourcepath. */
final class JdtParser {

    interface UnitConsumer {
        void accept(Path file, CompilationUnit unit);
    }

    interface FailureConsumer {
        void failed(Path file, Throwable error);
    }

    /**
     * Delivers each file to {@code consumer} at most once. When JDT fails inside a batch (including by rethrowing a
     * consumer exception), the batch's undelivered files are re-parsed one by one, so one bad file cannot drop the
     * rest; a file that fails on its own goes to {@code onFailure}. Errors other than StackOverflowError propagate.
     */
    void parse(List<Path> files, List<Path> classpath, List<Path> sourcepath, int batchSize, UnitConsumer consumer,
            FailureConsumer onFailure) {
        String[] classpathEntries = classpath.stream().map(Path::toString).toArray(String[]::new);
        String[] sourcepathEntries = sourcepath.stream().map(Path::toString).toArray(String[]::new);
        for (int from = 0; from < files.size(); from += batchSize) {
            List<Path> batch = files.subList(from, Math.min(files.size(), from + batchSize));
            Delivery delivery = new Delivery(consumer);
            try {
                createASTs(batch, classpathEntries, sourcepathEntries, delivery);
            } catch (RuntimeException | StackOverflowError batchError) {
                if (delivery.inProgress != null) {
                    onFailure.failed(delivery.inProgress, batchError);
                    delivery.inProgress = null;
                }
                for (Path file : batch) {
                    if (delivery.attempted.contains(file)) {
                        continue;
                    }
                    try {
                        createASTs(List.of(file), classpathEntries, sourcepathEntries, delivery);
                    } catch (RuntimeException | StackOverflowError fileError) {
                        onFailure.failed(file, fileError);
                        delivery.inProgress = null;
                    }
                }
            }
        }
    }

    /** Remembers which files reached the consumer; a file whose consumer call threw counts as attempted. */
    private static final class Delivery extends FileASTRequestor {

        private final UnitConsumer consumer;
        private final Set<Path> attempted = new HashSet<>();
        private Path inProgress;

        Delivery(UnitConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public void acceptAST(String sourceFilePath, CompilationUnit ast) {
            Path file = Path.of(sourceFilePath);
            if (!attempted.add(file)) {
                return;
            }
            inProgress = file;
            consumer.accept(file, ast);
            inProgress = null;
        }
    }

    private static void createASTs(List<Path> files, String[] classpath, String[] sourcepath, Delivery delivery) {
        String[] paths = files.stream().map(Path::toString).toArray(String[]::new);
        newParser(classpath, sourcepath).createASTs(paths, utf8(paths.length), new String[0], delivery, null);
    }

    /** Syntax only: no bindings and no method bodies. Enough to read a file's package, imports and type names. */
    static CompilationUnit parseWithoutBindings(String source) {
        ASTParser parser = syntaxParser();
        parser.setResolveBindings(false);
        parser.setIgnoreMethodBodies(true);
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static ASTParser syntaxParser() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.DISABLED);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return parser;
    }

    private static ASTParser newParser(String[] classpath, String[] sourcepath) {
        ASTParser parser = syntaxParser();
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setEnvironment(classpath, sourcepath, utf8(sourcepath.length), true);
        return parser;
    }

    private static String[] utf8(int count) {
        String[] encodings = new String[count];
        Arrays.fill(encodings, "UTF-8");
        return encodings;
    }
}
