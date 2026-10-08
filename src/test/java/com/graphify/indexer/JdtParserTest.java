package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdtParserTest {

    @TempDir
    Path dir;

    @Test
    void resolvesTypesFromOtherSourceRootsOnTheSourcepath() throws IOException {
        write("lib/src/main/java/com/acme/lib/Greeter.java", """
                package com.acme.lib;
                public class Greeter { public String greet(String name) { return name; } }
                """);
        Path app = write("app/src/main/java/com/acme/app/App.java", """
                package com.acme.app;
                import com.acme.lib.Greeter;
                public class App { String run() { return new Greeter().greet("x"); } }
                """);

        List<CompilationUnit> units = new ArrayList<>();
        new JdtParser().parse(List.of(app), List.of(),
                List.of(dir.resolve("lib/src/main/java"), dir.resolve("app/src/main/java")), 10,
                (file, unit) -> units.add(unit), JdtParserTest::unexpectedFailure);

        assertThat(units).hasSize(1);
        List<IMethodBinding> calls = new ArrayList<>();
        units.getFirst().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                calls.add(node.resolveMethodBinding());
                return true;
            }
        });
        assertThat(calls).singleElement().satisfies(binding -> {
            assertThat(binding).isNotNull();
            assertThat(binding.isRecovered()).isFalse();
            assertThat(binding.getDeclaringClass().getQualifiedName()).isEqualTo("com.acme.lib.Greeter");
        });
    }

    @Test
    void deliversEveryFileWhenSplitIntoBatches() throws IOException {
        List<Path> files = List.of(
                write("src/p/A.java", "package p; class A {}"),
                write("src/p/B.java", "package p; class B {}"),
                write("src/p/C.java", "package p; class C {}"));

        List<Path> seen = new ArrayList<>();
        new JdtParser().parse(files, List.of(), List.of(dir.resolve("src")), 2, (file, unit) -> seen.add(file),
                JdtParserTest::unexpectedFailure);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(files);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void failureInsideABatchRedeliversOnlyUndeliveredFilesAndReportsTheFailingOne() throws IOException {
        List<Path> files = List.of(
                write("src/p/A.java", "package p; class A {}"),
                write("src/p/B.java", "package p; class B {}"),
                write("src/p/C.java", "package p; class C {}"),
                write("src/p/D.java", "package p; class D {}"));
        Path failing = files.get(1);

        List<Path> seen = new ArrayList<>();
        List<Path> failed = new ArrayList<>();
        new JdtParser().parse(files, List.of(), List.of(dir.resolve("src")), 10, (file, unit) -> {
            if (file.equals(failing)) {
                throw new IllegalStateException("simulated JDT failure");
            }
            seen.add(file);
        }, (file, error) -> failed.add(file));

        assertThat(seen).containsExactlyInAnyOrder(files.get(0), files.get(2), files.get(3));
        assertThat(failed).containsExactly(failing);
    }

    private static void unexpectedFailure(Path file, Throwable error) {
        throw new AssertionError("JDT failed on " + file, error);
    }
}
