package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SchemaMigrationTest extends OracleIntegrationTest {

    @Test
    void createsTheCoreTables() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class)).contains(
                "app_setting", "audit_log", "scm_connection", "scm_repository", "maven_module",
                "symbol", "symbol_declaration", "usage");
    }

    @Test
    void indexesTheColumnsImpactQueriesFilterOn() {
        assertThat(jdbc.queryForList("SELECT LOWER(index_name) FROM user_indexes", String.class)).contains(
                "ix_usage_bfs", "ix_symbol_search", "ix_symbol_search_member", "ix_usage_from", "ix_usage_module", "ix_symbol_class_member",
                "ix_symbol_declaration_symbol", "ix_symbol_declaration_module", "uq_symbol_key");
    }

    @Test
    void theBfsIndexCoversTheImpactQuery() {
        assertThat(jdbc.queryForList("""
                SELECT LOWER(column_name) FROM user_ind_columns WHERE index_name = 'IX_USAGE_BFS'
                 ORDER BY column_position
                """, String.class))
                .containsExactly("to_symbol_id", "kind", "confidence", "from_symbol_id", "module_id", "id");
    }

    @Test
    void rejectsUnknownSymbolKinds() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO symbol (symbol_key, kind, class_fqn, display_signature, origin, name_only) "
                        + "VALUES ('p.A', 'WIDGET', 'p.A', 'A', 'SOURCE', 0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownUsageKinds() {
        long repoId = StoreFixtures.newRepository(jdbc, "schema-usage-kind");
        try {
            jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, '.', 'NONE')", repoId);
            long moduleId = jdbc.queryForObject("SELECT id FROM maven_module WHERE repo_id = ?", Long.class, repoId);
            jdbc.update("INSERT INTO symbol (symbol_key, kind, class_fqn, display_signature, origin, name_only) "
                    + "VALUES ('schema.UsageKind', 'CLASS', 'schema.UsageKind', 'UsageKind', 'SOURCE', 0)");
            long symbolId = jdbc.queryForObject("SELECT id FROM symbol WHERE symbol_key = 'schema.UsageKind'",
                    Long.class);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO usage (from_symbol_id, to_symbol_id, module_id, kind, confidence, file_path, line_no,
                                       column_no)
                    VALUES (?, ?, ?, 'WIDGET', 'EXACT', 'A.java', 1, 1)
                    """, symbolId, symbolId, moduleId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM usage WHERE module_id IN (SELECT id FROM maven_module WHERE repo_id = ?)", repoId);
            jdbc.update("DELETE FROM symbol WHERE symbol_key = 'schema.UsageKind'");
            jdbc.update("DELETE FROM maven_module WHERE repo_id = ?", repoId);
            jdbc.update("DELETE FROM scm_repository WHERE id = ?", repoId);
        }
    }

    @Test
    void seedsEntryPointsAndImpactRules() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entry_point_annotation WHERE enabled = 1", Integer.class))
                .isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT label FROM entry_point_annotation WHERE annotation_fqn = "
                + "'org.springframework.web.bind.annotation.PostMapping'", String.class)).isEqualTo("HTTP");
        Map<String, Object> call = jdbc.queryForMap(
                "SELECT propagates, shown_at_level1 FROM impact_relation_rule WHERE usage_kind = 'CALL'");
        Map<String, Object> typeRef = jdbc.queryForMap(
                "SELECT propagates, shown_at_level1 FROM impact_relation_rule WHERE usage_kind = 'TYPE_REF'");
        assertThat(((Number) call.get("PROPAGATES")).intValue()).isEqualTo(1);
        assertThat(((Number) typeRef.get("PROPAGATES")).intValue()).isZero();
        assertThat(((Number) typeRef.get("SHOWN_AT_LEVEL1")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impact_relation_rule", Integer.class)).isEqualTo(10);
    }

    @Test
    void searchColumnsHoldUpperCasedSimpleNames() {
        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, member_name, display_signature, origin, name_only)
                VALUES ('a.b.Outer$Inner#run()', 'METHOD', 'a.b.Outer$Inner', 'run', 'Inner.run()', 'SOURCE', 0)
                """);
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT search_class, search_member FROM symbol WHERE symbol_key = 'a.b.Outer$Inner#run()'");
            assertThat(row).containsEntry("SEARCH_CLASS", "INNER").containsEntry("SEARCH_MEMBER", "RUN");
        } finally {
            jdbc.update("DELETE FROM symbol WHERE symbol_key = 'a.b.Outer$Inner#run()'");
        }
    }

    @Test
    void createsTheAcquisitionTablesAndSeedsTheirSettings() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class))
                .contains("artifact_repository", "module_dependency", "index_run", "index_run_repo");
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_setting", String.class)).contains(
                "index.maven_executable", "index.maven_local_repository", "index.git_depth", "index.git_timeout",
                "index.source_roots", "scm.connect_timeout", "scm.read_timeout");
    }

    @Test
    void createsTheOrchestrationSchema() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class)).contains("index_lock");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM index_lock WHERE lock_name = 'INDEX'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT LOWER(table_name || '.' || column_name) FROM user_tab_columns
                 WHERE table_name IN ('INDEX_RUN', 'SCM_REPOSITORY', 'SCM_CONNECTION')
                """, String.class)).contains("index_run.cancel_requested", "index_run.error",
                "scm_repository.indexing_run_id", "scm_connection.last_sync_status", "scm_connection.last_sync_at",
                "scm_connection.last_sync_error");
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_setting", String.class))
                .contains("scm.max_deactivation_percent", "cleanup.batch_size", "store.connection_reserve");
    }

    @Test
    void createsTheAuthenticationSchema() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class))
                .contains("app_user", "local_account", "ldap_config");
        assertThat(jdbc.queryForObject("SELECT enabled || ':' || user_search_filter || ':' || username_attr "
                + "FROM ldap_config WHERE id = 1", String.class)).isEqualTo("0:(uid={0}):uid");
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_setting", String.class)).contains(
                "auth.password_min_length", "auth.ldap_connect_timeout", "auth.ldap_read_timeout",
                "auth.ldap_search_max_results");
    }

    @Test
    void anIndexRunRecordsOneOutcomePerRepositoryAndArtifactRepositoriesRememberTheirTest() {
        StoreFixtures.cleanIndexTables(jdbc);
        long repo = StoreFixtures.newRepository(jdbc, "uniq");
        jdbc.update("INSERT INTO index_run (trigger_type, scope, status, started_by) VALUES ('MANUAL', 'ALL', 'RUNNING', 't')");
        long run = jdbc.queryForObject("SELECT MAX(id) FROM index_run", Long.class);
        jdbc.update("INSERT INTO index_run_repo (run_id, repo_id, status) VALUES (?, ?, 'SUCCESS')", run, repo);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO index_run_repo (run_id, repo_id, status) VALUES (?, ?, 'FAILED')",
                run, repo)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT LOWER(column_name) FROM user_tab_columns WHERE table_name = "
                + "'ARTIFACT_REPOSITORY'", String.class)).contains("last_test_status", "last_test_at");
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_setting", String.class))
                .contains("artifact.test_timeout");
        StoreFixtures.cleanIndexTables(jdbc);
    }

    @Test
    void connectionTypesCoverBitbucketGitHubAndGit() {
        try {
            jdbc.update("INSERT INTO scm_connection (name, type, base_url) VALUES ('v10-bb', 'BITBUCKET_DC', 'https://bb')");
            jdbc.update("INSERT INTO scm_connection (name, type, base_url) VALUES ('v10-gh', 'GITHUB', 'https://gh')");
            jdbc.update("INSERT INTO scm_connection (name, type, base_url, repository_urls) "
                    + "VALUES ('v10-git', 'GIT', 'https://git', ?)", "https://git/a.git\nhttps://git/b.git");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_connection WHERE name LIKE 'v10-%'",
                    Integer.class)).isEqualTo(3);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO scm_connection (name, type, base_url) VALUES ('v10-gl', 'GITLAB', 'https://gl')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM scm_connection WHERE name LIKE 'v10-%'");
        }
    }

    @Test
    void ownRepositoriesAreOffByDefault() {
        try {
            jdbc.update("INSERT INTO scm_connection (name, type, base_url) VALUES ('v11-gh', 'GITHUB', 'https://gh')");
            assertThat(jdbc.queryForObject("SELECT include_own_repositories FROM scm_connection WHERE name = 'v11-gh'",
                    Integer.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("INSERT INTO scm_connection (name, type, base_url, "
                    + "include_own_repositories) VALUES ('v11-bad', 'GITHUB', 'https://gh', 2)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM scm_connection WHERE name LIKE 'v11-%'");
        }
    }

    @Test
    void seedsTheMavenCheckTimeout() {
        assertThat(jdbc.queryForObject(
                "SELECT value_type || ' ' || setting_value FROM app_setting WHERE setting_key = 'index.maven_check_timeout'",
                String.class)).isEqualTo("DURATION PT30S");
    }

    @Test
    void addsTheWorkspaceArtifactColumnsAndTheSearchDepth() {
        assertThat(jdbc.queryForObject("SELECT value_type || ' ' || setting_value || ' ' || min_value || ' ' || max_value "
                + "FROM app_setting WHERE setting_key = 'index.pom_search_depth'", String.class)).isEqualTo("INT 3 1 10");
        assertThat(jdbc.queryForList("SELECT table_name || '.' || column_name FROM user_tab_columns WHERE "
                + "(table_name = 'SCM_REPOSITORY' AND column_name = 'LAST_INSTALLED_COMMIT') "
                + "OR (table_name = 'MAVEN_MODULE' AND column_name = 'PACKAGING') "
                + "OR (table_name = 'INDEX_RUN_REPO' AND column_name IN ('ARTIFACT_INSTALL', 'ARTIFACT_INSTALL_ERROR'))",
                String.class)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT search_condition_vc FROM user_constraints "
                + "WHERE constraint_name = 'CK_INDEX_RUN_REPO_INSTALL'", String.class))
                .contains("INSTALLED", "UP_TO_DATE", "FAILED", "CYCLE_FAILED");
    }
}
