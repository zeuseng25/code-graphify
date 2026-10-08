package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.TestJars;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The plan-2 acceptance: common-lib and order-service are separate repositories, order-service sees common-lib
 * only as a jar, and SQL still answers "which projects use MoneyUtil#format(int)".
 */
class CrossRepositoryUsageTest extends OracleIntegrationTest {

    private static final String FORMAT_INT = "com.corp.common.MoneyUtil#format(int)";

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    Path work;

    @BeforeEach
    void clean() {
        StoreFixtures.cleanIndexTables(jdbc);
    }

    @Test
    void usageInOneRepositoryJoinsTheDeclarationInAnother() throws Exception {
        Path repo = Path.of(getClass().getResource("/fixtures/corp-repo").toURI());
        Path commonSources = repo.resolve("common-lib/src/main/java");
        Path commonJar = TestJars.jar(work, "common-lib", sources(commonSources), Set.of());
        long orderRepo = StoreFixtures.newRepository(jdbc, "order-service");
        long commonRepo = StoreFixtures.newRepository(jdbc, "common-lib");

        // order-service first: it only knows MoneyUtil as BINARY; common-lib's SOURCE row must win afterwards.
        writer.replace(new RepositoryIndex(orderRepo, "o1",
                List.of(new ModuleRecord("order-service", "com.corp", "order-service", "1", ClasspathMode.FULL)),
                new JavaRepositoryIndexer().index(new IndexRequest(repo, List.of(new ModuleSource("order-service",
                        List.of(repo.resolve("order-service/src/main/java")), List.of(commonJar))),
                        new IndexerOptions(50, 300)))));
        writer.replace(new RepositoryIndex(commonRepo, "c1",
                List.of(new ModuleRecord("common-lib", "com.corp", "common-lib", "1", ClasspathMode.FULL)),
                new JavaRepositoryIndexer().index(new IndexRequest(repo,
                        List.of(new ModuleSource("common-lib", List.of(commonSources), List.of())),
                        new IndexerOptions(50, 300)))));

        assertThat(jdbc.queryForList("""
                SELECT DISTINCT r.slug
                  FROM usage u
                  JOIN symbol s ON s.id = u.to_symbol_id
                  JOIN maven_module m ON m.id = u.module_id
                  JOIN scm_repository r ON r.id = m.repo_id
                 WHERE s.symbol_key = ?
                """, String.class, FORMAT_INT)).containsExactly("order-service");
        assertThat(jdbc.queryForList("""
                SELECT r.slug
                  FROM symbol_declaration d
                  JOIN symbol s ON s.id = d.symbol_id
                  JOIN maven_module m ON m.id = d.module_id
                  JOIN scm_repository r ON r.id = m.repo_id
                 WHERE s.symbol_key = ?
                """, String.class, FORMAT_INT)).containsExactly("common-lib");
        assertThat(jdbc.queryForObject("SELECT origin FROM symbol WHERE symbol_key = ?", String.class, FORMAT_INT))
                .isEqualTo("SOURCE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage u JOIN symbol s ON s.id = u.to_symbol_id
                 WHERE s.symbol_key = ? AND u.confidence = 'EXACT'
                """, Integer.class, FORMAT_INT)).isEqualTo(2);
    }

    private static Map<String, String> sources(Path root) throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        return sources;
    }
}
