package com.graphify.testsupport;

import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.TestJars;
import com.graphify.indexer.model.IndexResult;
import com.graphify.store.ClasspathMode;
import com.graphify.store.ModuleRecord;
import com.graphify.store.RepositoryIndex;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.store.StoreFixtures;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Indexes {@code fixtures/shop} into Oracle as two repositories:
 * <ul>
 *   <li>{@code shop-lib} (module {@code shop-lib}, from source)</li>
 *   <li>{@code shop-api} (module {@code shop-api} with shop-lib's jar on its classpath, and module
 *       {@code shop-legacy} with no classpath)</li>
 * </ul>
 */
public final class ShopFixture {

    public static final String LIB_REPO = "shop-lib";
    public static final String API_REPO = "shop-api";

    private ShopFixture() {
    }

    public static void load(JdbcTemplate jdbc, RepositoryIndexWriter writer, Path workDir) throws IOException {
        StoreFixtures.cleanIndexTables(jdbc);
        Path root = root();
        Path libSources = root.resolve("shop-lib/src/main/java");
        Path libJar = TestJars.jar(workDir, "shop-lib", sources(libSources), Set.of());
        long libRepo = StoreFixtures.newRepository(jdbc, LIB_REPO);
        long apiRepo = StoreFixtures.newRepository(jdbc, API_REPO);

        writer.replace(new RepositoryIndex(libRepo, "lib-1",
                List.of(new ModuleRecord("shop-lib", "com.graphify.testfixture.shop", "shop-lib", "1.0.0", ClasspathMode.FULL)),
                index(root, new ModuleSource("shop-lib", List.of(libSources), List.of()))));
        writer.replace(new RepositoryIndex(apiRepo, "api-1",
                List.of(new ModuleRecord("shop-api", "com.graphify.testfixture.shop", "shop-api", "1.0.0", ClasspathMode.FULL),
                        new ModuleRecord("shop-legacy", "com.graphify.testfixture.shop", "shop-legacy", "1.0.0", ClasspathMode.NONE)),
                index(root,
                        new ModuleSource("shop-api", List.of(root.resolve("shop-api/src/main/java")), List.of(libJar)),
                        new ModuleSource("shop-legacy", List.of(root.resolve("shop-legacy/src/main/java")),
                                List.of()))));
    }

    public static long symbolId(JdbcTemplate jdbc, String key) {
        return jdbc.queryForObject("SELECT id FROM symbol WHERE symbol_key = ?", Long.class, key);
    }

    /** The id of a fixture repository; the fixture's slugs are unique. */
    public static long repositoryId(JdbcTemplate jdbc, String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    private static IndexResult index(Path root, ModuleSource... modules) {
        return new JavaRepositoryIndexer().index(new IndexRequest(root, List.of(modules), new IndexerOptions(50, 300)));
    }

    private static Path root() {
        try {
            return Path.of(ShopFixture.class.getResource("/fixtures/shop").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
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
