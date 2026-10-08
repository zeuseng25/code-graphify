package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.store.StoreFixtures;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ScmConnectionsTest extends OracleIntegrationTest {

    @Autowired
    ScmConnections connections;

    @Autowired
    SecretCipher cipher;

    private long id;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("""
                INSERT INTO scm_connection (name, type, base_url, username, secret_enc, include_projects, exclude_repos)
                VALUES ('corp', 'BITBUCKET_DC', 'https://scm.corp', 'bob', ?, ' SHOP , PAY ', 'SHOP/old-*')
                """, cipher.encrypt("s3cret"));
        jdbc.update("INSERT INTO scm_connection (name, type, base_url, enabled) VALUES ('off', 'BITBUCKET_DC', "
                + "'https://off', 0)");
        id = jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = 'corp'", Long.class);
    }

    @Test
    void readsEnabledConnectionsWithDecryptedSecretsAndTrimmedLists() {
        assertThat(connections.enabled()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("corp");
            assertThat(c.secret()).isEqualTo("s3cret");
            assertThat(c.includeProjects()).containsExactly("SHOP", "PAY");
            assertThat(c.excludeRepos()).containsExactly("SHOP/old-*");
        });
        assertThat(connections.find(id)).isPresent();
    }

    @Test
    void recordsTheLastSyncMaskedAndCut() {
        connections.recordSync(id, ConnectionSyncStatus.FAILED, "GET https://bob:pw123@scm.corp/x " + "y".repeat(5000));

        assertThat(jdbc.queryForObject("SELECT last_sync_status FROM scm_connection WHERE id = ?", String.class, id))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_sync_error FROM scm_connection WHERE id = ?", String.class, id))
                .doesNotContain("pw123").contains("***@scm.corp");
        assertThat(jdbc.queryForObject("SELECT LENGTHB(last_sync_error) FROM scm_connection WHERE id = ?",
                Integer.class, id)).isEqualTo(4000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_connection WHERE id = ? AND last_sync_at IS NOT NULL",
                Integer.class, id)).isEqualTo(1);

        connections.recordSync(id, ConnectionSyncStatus.SUCCESS, null);

        assertThat(jdbc.queryForObject("SELECT last_sync_status || ':' || NVL(last_sync_error, '-') FROM scm_connection "
                + "WHERE id = ?", String.class, id)).isEqualTo("SUCCESS:-");
    }

    @Test
    void roundTripsAGitConnectionWithItsUrls() {
        List<String> urls = List.of("https://git.corp/scm/a/x.git", "https://git.corp/scm/b/y.git",
                "https://git.corp/scm/z.git");
        long gitId = connections.insert("git", ScmType.GIT, "https://git.corp/scm", null, null, List.of(), List.of(),
                urls, false, true);

        assertThat(connections.find(gitId).orElseThrow().repositoryUrls()).isEqualTo(urls);
        assertThat(connections.view(gitId).orElseThrow().repositoryUrls()).isEqualTo(urls);

        connections.update(gitId, "git", ScmType.GIT, "https://git.corp/scm", null, null, List.of(), List.of(),
                urls.subList(0, 1), false, true);
        assertThat(connections.find(gitId).orElseThrow().repositoryUrls()).isEqualTo(urls.subList(0, 1));
        assertThat(connections.find(id).orElseThrow().repositoryUrls()).isEmpty();
    }

    @Test
    void storesUrlListsLargerThanAVarcharBind() {
        List<String> urls = IntStream.range(0, 400)
                .mapToObj(i -> "https://git.corp/scm/team/" + "r".repeat(60) + "-" + i + ".git").toList();
        assertThat(String.join("\n", urls).length()).isGreaterThan(32_767);

        long gitId = connections.insert("big", ScmType.GIT, "https://git.corp/scm", null, null, List.of(), List.of(),
                urls, false, true);
        assertThat(connections.find(gitId).orElseThrow().repositoryUrls()).isEqualTo(urls);

        List<String> more = IntStream.range(0, 450)
                .mapToObj(i -> "https://git.corp/scm/team/" + "q".repeat(60) + "-" + i + ".git").toList();
        connections.update(gitId, "big", ScmType.GIT, "https://git.corp/scm", null, null, List.of(), List.of(), more,
                false, true);
        assertThat(connections.find(gitId).orElseThrow().repositoryUrls()).isEqualTo(more);
    }

    @Test
    void storesWhetherTheTokenOwnersRepositoriesAreListed() {
        long id = connections.insert("own", ScmType.GITHUB, "https://api.github.test", null, "tok", List.of(),
                List.of(), List.of(), true, true);
        try {
            assertThat(connections.find(id).orElseThrow().includeOwnRepositories()).isTrue();
            assertThat(connections.view(id).orElseThrow().includeOwnRepositories()).isTrue();

            connections.update(id, "own", ScmType.GITHUB, "https://api.github.test", null, "tok", List.of("acme"),
                    List.of(), List.of(), false, true);
            assertThat(connections.find(id).orElseThrow().includeOwnRepositories()).isFalse();
        } finally {
            jdbc.update("DELETE FROM scm_connection WHERE id = ?", id);
        }
    }
}
