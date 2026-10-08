package com.graphify.testsupport;

import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.store.ClasspathMode;
import com.graphify.store.ModuleRecord;
import com.graphify.store.RepositoryIndex;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.store.StoreFixtures;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Indexes {@code fixtures/graph-repo} into Oracle as one repository with modules {@code core} (packages com.g.a and
 * com.g.b, which depend on each other) and {@code app} (com.g.c and com.g.web, whose {@code Api#handle()} carries
 * {@code @com.g.web.Endpoint}).
 */
public final class GraphFixture {

    public static final String REPO = "graph-repo";
    public static final String COMMIT = "graph-1";
    public static final String ENTRY_ANNOTATION = "com.g.web.Endpoint";

    private GraphFixture() {
    }

    public static long load(JdbcTemplate jdbc, RepositoryIndexWriter writer) {
        StoreFixtures.cleanIndexTables(jdbc);
        Path root = root();
        long repo = StoreFixtures.newRepository(jdbc, REPO);
        Path core = root.resolve("core/src/main/java");
        Path app = root.resolve("app/src/main/java");
        writer.replace(new RepositoryIndex(repo, COMMIT,
                List.of(new ModuleRecord("core", "com.g", "core", "1.0.0", ClasspathMode.FULL),
                        new ModuleRecord("app", "com.g", "app", "1.0.0", ClasspathMode.FULL)),
                new JavaRepositoryIndexer().index(new IndexRequest(root, List.of(
                        new ModuleSource("core", List.of(core), List.of()),
                        new ModuleSource("app", List.of(app), List.of())), new IndexerOptions(50, 300)))));
        return repo;
    }

    private static Path root() {
        try {
            return Path.of(GraphFixture.class.getResource("/fixtures/graph-repo").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
