package com.graphify.testsupport;

import com.graphify.common.crypto.SecretCipher;
import com.graphify.indexer.TestJars;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shop fixture as production sees it: a fake Bitbucket lists three git repositories (shop-lib; shop-api, which
 * depends on shop-lib 1.0.0 through Maven; docs, without Java), shop-lib is published to a file-based Maven
 * repository registered in artifact_repository, and the scm_connection {@value #CONNECTION} points at the fake.
 */
public final class ShopScm implements AutoCloseable {

    public static final String TOKEN = "bb-token-123";
    public static final String CONNECTION = "corp";
    /** The Maven groupId, inside the fixture namespace: runs install shop-lib into the local repository. */
    public static final String GROUP = "com.graphify.testfixture.shop";

    private static final String SOURCES = "src/main/java/";

    private final FakeBitbucket bitbucket;
    private final Path apiBare;
    private final Set<String> apiJavaFiles;

    private ShopScm(FakeBitbucket bitbucket, Path apiBare, Set<String> apiJavaFiles) {
        this.bitbucket = bitbucket;
        this.apiBare = apiBare;
        this.apiJavaFiles = apiJavaFiles;
    }

    public static ShopScm create(JdbcTemplate jdbc, SecretCipher cipher, Path dir)
            throws IOException, URISyntaxException {
        Path shop = Path.of(ShopScm.class.getResource("/fixtures/shop").toURI());
        Map<String, String> libSources = sources(shop.resolve("shop-lib/src/main/java"));
        Map<String, String> apiSources = sources(shop.resolve("shop-api/src/main/java"));

        Path repo = MavenFixtures.fileRepository(dir);
        Map<String, String> libJarSources = new HashMap<>();
        libSources.forEach((path, source) -> libJarSources.put(path.substring(SOURCES.length()), source));
        MavenFixtures.publish(repo, GROUP, "shop-lib", "1.0.0",
                TestJars.jar(dir, "shop-lib", libJarSources, Set.of()));
        jdbc.update("INSERT INTO artifact_repository (name, url) VALUES ('fixture', ?)", repo.toUri().toString());

        Map<String, String> lib = new HashMap<>(libSources);
        lib.put("pom.xml", MavenFixtures.pom(GROUP, "shop-lib", "1.0.0", "", ""));
        Map<String, String> api = new HashMap<>(apiSources);
        api.put("pom.xml", MavenFixtures.pom(GROUP, "shop-api", "1.0.0", "",
                MavenFixtures.dependency(GROUP, "shop-lib", "1.0.0")));
        Path libBare = GitFixtures.bareRepository(dir, "shop-lib", lib);
        Path apiBare = GitFixtures.bareRepository(dir, "shop-api", api);
        Path docsBare = GitFixtures.bareRepository(dir, "docs", Map.of("README.md", "# docs only"));

        FakeBitbucket bitbucket = new FakeBitbucket().start().requireAuthorization("Bearer " + TOKEN)
                .addRepository("SHOP", "shop-lib", GitFixtures.url(libBare))
                .addRepository("SHOP", "shop-api", GitFixtures.url(apiBare))
                .addRepository("SHOP", "docs", GitFixtures.url(docsBare));
        jdbc.update("INSERT INTO scm_connection (name, type, base_url, secret_enc) VALUES (?, 'BITBUCKET_DC', ?, ?)",
                CONNECTION, bitbucket.baseUrl(), cipher.encrypt(TOKEN));
        return new ShopScm(bitbucket, apiBare, Set.copyOf(apiSources.keySet()));
    }

    public FakeBitbucket bitbucket() {
        return bitbucket;
    }

    public Path apiBare() {
        return apiBare;
    }

    /** The repository-relative paths of shop-api's Java files. */
    public Set<String> apiJavaFiles() {
        return apiJavaFiles;
    }

    public long connectionId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = ?", Long.class, CONNECTION);
    }

    @Override
    public void close() {
        bitbucket.close();
    }

    private static Map<String, String> sources(Path root) throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(SOURCES + root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        return sources;
    }
}
