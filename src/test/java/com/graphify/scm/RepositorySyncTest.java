package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.FakeBitbucket;
import com.graphify.testsupport.SettingsOverride;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepositorySyncTest extends OracleIntegrationTest {

    @Autowired
    RepositorySync sync;

    private FakeBitbucket bitbucket;
    private ScmConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        bitbucket = new FakeBitbucket().start();
        jdbc.update("INSERT INTO scm_connection (name, type, base_url) VALUES ('corp', 'BITBUCKET_DC', ?)",
                bitbucket.baseUrl());
        long id = jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = 'corp'", Long.class);
        connection = new ScmConnection(id, "corp", ScmType.BITBUCKET_DC, bitbucket.baseUrl(), null, null, List.of(),
                List.of());
    }

    @AfterEach
    void tearDown() {
        bitbucket.close();
    }

    private List<String> rows() {
        return jdbc.queryForList("SELECT project_key || '/' || slug || ':' || active || ':' || clone_url "
                + "FROM scm_repository ORDER BY project_key, slug", String.class);
    }

    @Test
    void addsUpdatesDeactivatesAndReactivatesRepositories() {
        bitbucket.addRepository("SHOP", "api", "https://scm/a.git").addRepository("SHOP", "lib", "https://scm/l.git");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(2, 2, 0, 0));
        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:1:https://scm/l.git");

        long libId = jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = 'lib'", Long.class);
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, '.', 'FULL')", libId);
        bitbucket.removeRepository("SHOP", "lib");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(1, 0, 0, 1));
        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:0:https://scm/l.git");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maven_module", Integer.class)).isZero();

        bitbucket.addRepository("SHOP", "lib", "https://scm/l2.git");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(2, 0, 1, 0));
        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:1:https://scm/l2.git");
    }

    @Test
    void sameSlugInAnotherProjectIsADifferentRepository() {
        bitbucket.addRepository("SHOP", "api", "https://scm/s.git").addRepository("PAY", "api", "https://scm/p.git");

        sync.sync(connection);

        assertThat(rows()).containsExactly("PAY/api:1:https://scm/p.git", "SHOP/api:1:https://scm/s.git");
    }

    @Test
    void aListingThatCannotBeReadDeactivatesNothing() {
        bitbucket.addRepository("SHOP", "api", "https://scm/a.git").addRepository("SHOP", "lib", "https://scm/l.git");
        sync.sync(connection);
        long libId = jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = 'lib'", Long.class);
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, '.', 'FULL')", libId);
        bitbucket.respondRaw(200, "{}");

        assertThatThrownBy(() -> sync.sync(connection)).isInstanceOf(ScmException.class);

        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:1:https://scm/l.git");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maven_module", Integer.class)).isEqualTo(1);
    }

    @Test
    void aListedRepositoryWhoseUrlBecomesTooLongStaysActiveWithItsIndex() {
        bitbucket.addRepository("SHOP", "lib", "https://scm/l.git");
        sync.sync(connection);
        long libId = jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = 'lib'", Long.class);
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, '.', 'FULL')", libId);
        bitbucket.removeRepository("SHOP", "lib");
        bitbucket.addRepository("SHOP", "lib", "https://scm/" + "x".repeat(1001) + ".git");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(1, 0, 0, 0));

        assertThat(rows()).containsExactly("SHOP/lib:1:https://scm/l.git");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maven_module", Integer.class)).isEqualTo(1);
    }

    @Autowired
    AppSettings settings;

    @Test
    void aMassDisappearanceIsNotDeactivated() {
        bitbucket.addRepository("SHOP", "a", "https://scm/a.git").addRepository("SHOP", "b", "https://scm/b.git")
                .addRepository("SHOP", "c", "https://scm/c.git");
        sync.sync(connection);
        long aId = jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = 'a'", Long.class);
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, '.', 'FULL')", aId);
        bitbucket.removeRepository("SHOP", "a");
        bitbucket.removeRepository("SHOP", "b");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(1, 0, 0, 0, 2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE active = 1", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maven_module", Integer.class)).isEqualTo(1);

        SettingsOverride overrides = new SettingsOverride(settings);
        try {
            overrides.set(SettingKeys.SCM_MAX_DEACTIVATION_PERCENT, "100");

            assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(1, 0, 0, 2, 0));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE active = 1", Integer.class))
                    .isEqualTo(1);
        } finally {
            overrides.restore();
        }
    }

    private void indexAllThenRepoint() {
        // what an index followed by ScmConnectionAdministration's repoint leaves behind
        jdbc.update("UPDATE scm_repository SET last_indexed_commit = 'c1', active = 0");
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) SELECT id, '.', 'FULL' FROM scm_repository");
    }

    private List<String> indexedSlugs() {
        return jdbc.queryForList("SELECT r.slug FROM maven_module m JOIN scm_repository r ON r.id = m.repo_id "
                + "ORDER BY r.slug", String.class);
    }

    @Test
    void aRepositoryLeftInactiveByARepointIsPurgedWhenTheNewHostDoesNotListIt() {
        bitbucket.addRepository("SHOP", "api", "https://scm/a.git").addRepository("SHOP", "lib", "https://scm/l.git")
                .addRepository("SHOP", "web", "https://scm/w.git");
        sync.sync(connection);
        indexAllThenRepoint();
        bitbucket.removeRepository("SHOP", "web");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(2, 0, 2, 0, 0, 1, 0));
        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:1:https://scm/l.git",
                "SHOP/web:0:https://scm/w.git");
        assertThat(indexedSlugs()).containsExactly("api", "lib");
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE slug = 'web'",
                String.class)).isNull();

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(2, 0, 0, 0));
    }

    @Test
    void aMassDisappearanceAfterARepointPurgesNothing() {
        bitbucket.addRepository("SHOP", "api", "https://scm/a.git").addRepository("SHOP", "lib", "https://scm/l.git")
                .addRepository("SHOP", "web", "https://scm/w.git");
        sync.sync(connection);
        indexAllThenRepoint();
        bitbucket.removeRepository("SHOP", "api");
        bitbucket.removeRepository("SHOP", "lib");
        bitbucket.removeRepository("SHOP", "web");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(0, 0, 0, 0, 0, 0, 3));
        assertThat(indexedSlugs()).containsExactly("api", "lib", "web");
    }

    private void addRepositories(int count) {
        for (int i = 0; i < count; i++) {
            bitbucket.addRepository("SHOP", "r" + i, "https://scm/r" + i + ".git");
        }
    }

    @Test
    void aRepointListingEightOfTenIndexedRepositoriesPurgesTheOtherTwo() {
        addRepositories(10);
        sync.sync(connection);
        indexAllThenRepoint();
        bitbucket.removeRepository("SHOP", "r8");
        bitbucket.removeRepository("SHOP", "r9");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(8, 0, 8, 0, 0, 2, 0));
        assertThat(indexedSlugs()).hasSize(8).doesNotContain("r8", "r9");
    }

    @Test
    void aHeldBackPurgeStaysHeldBackUntilThePercentageIsRaised() {
        addRepositories(10);
        sync.sync(connection);
        indexAllThenRepoint();
        for (int i = 4; i < 10; i++) {
            bitbucket.removeRepository("SHOP", "r" + i);
        }

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(4, 0, 4, 0, 0, 0, 6));
        // the listed repositories are active now, but the denominator is every indexed repository: stable, not escalating
        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(4, 0, 0, 0, 0, 0, 6));
        assertThat(indexedSlugs()).hasSize(10);

        SettingsOverride overrides = new SettingsOverride(settings);
        try {
            overrides.set(SettingKeys.SCM_MAX_DEACTIVATION_PERCENT, "100");

            assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(4, 0, 0, 0, 0, 6, 0));
            assertThat(indexedSlugs()).containsExactly("r0", "r1", "r2", "r3");
        } finally {
            overrides.restore();
        }
    }
}
