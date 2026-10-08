package com.graphify.store;

import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test data for the index tables. Settings rows are never touched here. */
public final class StoreFixtures {

    private StoreFixtures() {
    }

    public static void cleanIndexTables(JdbcTemplate jdbc) {
        jdbc.update("UPDATE index_lock SET holder = NULL, acquired_at = NULL");
        jdbc.update("UPDATE scm_repository SET indexing_run_id = NULL");
        jdbc.update("DELETE FROM repo_graph_cycle");
        jdbc.update("DELETE FROM repo_graph_community");
        jdbc.update("DELETE FROM repo_graph_metric");
        jdbc.update("DELETE FROM repo_graph_analysis");
        jdbc.update("DELETE FROM index_run_repo");
        jdbc.update("DELETE FROM index_run");
        jdbc.update("DELETE FROM usage");
        jdbc.update("DELETE FROM symbol_declaration");
        jdbc.update("UPDATE symbol SET parent_id = NULL");
        jdbc.update("DELETE FROM symbol");
        jdbc.update("DELETE FROM module_dependency");
        jdbc.update("DELETE FROM maven_module");
        jdbc.update("DELETE FROM scm_repository");
        jdbc.update("DELETE FROM scm_connection");
        jdbc.update("DELETE FROM artifact_repository");
    }

    /** Inserts a connection (if needed) and a repository with this slug; returns the repository id. */
    public static long newRepository(JdbcTemplate jdbc, String slug) {
        jdbc.update("""
                MERGE INTO scm_connection c USING (SELECT 'test-connection' AS name FROM dual) n ON (c.name = n.name)
                WHEN NOT MATCHED THEN INSERT (name, type, base_url) VALUES (n.name, 'BITBUCKET_DC', 'https://scm.test')
                """);
        Long connectionId = jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = 'test-connection'",
                Long.class);
        jdbc.update("INSERT INTO scm_repository (connection_id, project_key, slug, clone_url) VALUES (?, 'TEST', ?, ?)",
                connectionId, slug, "https://scm.test/scm/test/" + slug + ".git");
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    public static Symbol symbol(String key, SymbolKind kind, SymbolOrigin origin, boolean nameOnly) {
        String classFqn = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
        String member = key.contains("#") ? key.substring(key.indexOf('#') + 1) : null;
        String parent = key.contains("#") ? classFqn : null;
        return new Symbol(key, kind, classFqn, member, key, parent, origin, nameOnly);
    }
}
