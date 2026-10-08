package com.graphify.indexer;

import static com.graphify.indexer.IndexResults.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.UsageKind;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobustnessTest {

    @TempDir
    Path dir;

    @Test
    void brokenFileDoesNotStopIndexing() throws Exception {
        IndexResult result = TempRepo.at(dir)
                .java("app", "p/Broken.java", """
                        package p;
                        class Broken {
                            void f() { int x = ; }
                            int g() { return "a".length(); }
                        }
                        """)
                .java("app", "p/Clean.java", """
                        package p;
                        class Clean { int ok() { return "b".length(); } }
                        """)
                .index();

        assertThat(usage(result, "p.Clean#ok()", UsageKind.CALL, "java.lang.String#length()").confidence())
                .isEqualTo(Confidence.EXACT);
        usage(result, "p.Broken#g()", UsageKind.CALL, "java.lang.String#length()");
    }

    @Test
    void nonUtf8FileIsIndexed() throws Exception {
        ByteArrayOutputStream iso88599 = new ByteArrayOutputStream();
        iso88599.writeBytes("package p;\n// ".getBytes(StandardCharsets.US_ASCII));
        iso88599.write(0xFE); // 'ş' in ISO-8859-9, invalid as UTF-8
        iso88599.writeBytes("\nclass Enc { int f() { return \"a\".length(); } }\n".getBytes(StandardCharsets.US_ASCII));

        IndexResult result = TempRepo.at(dir).rawJava("app", "p/Enc.java", iso88599.toByteArray()).index();

        assertThat(usage(result, "p.Enc#f()", UsageKind.CALL, "java.lang.String#length()").line()).isEqualTo(3);
    }

    @Test
    void missingSourceRootsAndEmptyModuleListsAreHarmless() {
        JavaRepositoryIndexer indexer = new JavaRepositoryIndexer();
        IndexerOptions options = new IndexerOptions(10, 100);

        IndexResult missingRoot = indexer.index(new IndexRequest(dir,
                List.of(new ModuleSource("ghost", List.of(dir.resolve("ghost/src/main/java")), List.of())), options));
        IndexResult noModules = indexer.index(new IndexRequest(dir, List.of(), options));

        assertThat(missingRoot.usages()).isEmpty();
        assertThat(missingRoot.symbols()).isEmpty();
        assertThat(noModules.usages()).isEmpty();
    }

    @Test
    void unreadableSourceRootIsAWarningNotAnException() throws Exception {
        TempRepo repo = TempRepo.at(dir)
                .java("app", "p/Clean.java", "package p; class Clean { int ok() { return \"b\".length(); } }")
                .java("locked", "q/Hidden.java", "package q; class Hidden {}");
        Path lockedRoot = dir.resolve("locked/src/main/java");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(lockedRoot);
        Files.setPosixFilePermissions(lockedRoot, Set.of());
        try {
            assumeFalse(Files.isReadable(lockedRoot), "running with privileges that ignore directory permissions");

            IndexResult result = repo.index();

            usage(result, "p.Clean#ok()", UsageKind.CALL, "java.lang.String#length()");
            assertThat(result.warnings()).anySatisfy(warning -> {
                assertThat(warning.modulePath()).isEqualTo("locked");
                assertThat(warning.filePath()).isEqualTo("locked/src/main/java");
                assertThat(warning.message()).startsWith("Source root skipped: ");
            });
        } finally {
            Files.setPosixFilePermissions(lockedRoot, original);
        }
    }
}
