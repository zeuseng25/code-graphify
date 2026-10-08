package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.SettingsOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SymbolCleanupTest extends OracleIntegrationTest {

    @Autowired
    SymbolCleanup cleanup;

    private long moduleId;

    @BeforeEach
    void clean() {
        StoreFixtures.cleanIndexTables(jdbc);
        long repoId = StoreFixtures.newRepository(jdbc, "cleanup");
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, '.', 'NONE')", repoId);
        moduleId = jdbc.queryForObject("SELECT id FROM maven_module WHERE repo_id = ?", Long.class, repoId);
    }

    private long symbol(String key, Long parentId) {
        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, display_signature, parent_id, origin, name_only)
                VALUES (?, 'CLASS', ?, ?, ?, 'SOURCE', 0)
                """, key, key, key, parentId);
        return jdbc.queryForObject("SELECT id FROM symbol WHERE symbol_key = ?", Long.class, key);
    }

    private void usage(long fromId, long toId) {
        jdbc.update("""
                INSERT INTO usage (from_symbol_id, to_symbol_id, module_id, kind, confidence, file_path, line_no,
                                   column_no)
                VALUES (?, ?, ?, 'CALL', 'EXACT', 'A.java', 1, 1)
                """, fromId, toId, moduleId);
    }

    private void declaration(long symbolId) {
        jdbc.update("INSERT INTO symbol_declaration (symbol_id, module_id, file_path, line_no) VALUES (?, ?, 'A.java', 1)",
                symbolId, moduleId);
    }

    private boolean exists(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM symbol WHERE symbol_key = ?", Integer.class, key) == 1;
    }

    @Test
    void symbolReferencedOnlyAsUsageTargetSurvives() {
        long from = symbol("p.From", null);
        long to = symbol("p.To", null);
        declaration(from);
        usage(from, to);

        assertThat(cleanup.deleteOrphans()).isZero();
        assertThat(exists("p.To")).isTrue();
    }

    @Test
    void symbolReferencedOnlyAsUsageSourceSurvives() {
        long from = symbol("p.From", null);
        long to = symbol("p.To", null);
        declaration(to);
        usage(from, to);

        assertThat(cleanup.deleteOrphans()).isZero();
        assertThat(exists("p.From")).isTrue();
    }

    @Test
    void symbolWithOnlyADeclarationSurvives() {
        declaration(symbol("p.Declared", null));

        assertThat(cleanup.deleteOrphans()).isZero();
        assertThat(exists("p.Declared")).isTrue();
    }

    @Test
    void unreferencedParentOfAReferencedChildSurvives() {
        long parent = symbol("p.Owner", null);
        long child = symbol("p.Owner#run()", parent);
        declaration(child);

        assertThat(cleanup.deleteOrphans()).isZero();
        assertThat(exists("p.Owner")).isTrue();
        assertThat(jdbc.queryForObject("SELECT parent_id FROM symbol WHERE id = ?", Long.class, child))
                .isEqualTo(parent);
    }

    @Test
    void unreferencedChildlessSymbolIsDeleted() {
        declaration(symbol("p.Kept", null));
        symbol("p.Orphan", null);

        assertThat(cleanup.deleteOrphans()).isEqualTo(1);
        assertThat(exists("p.Orphan")).isFalse();
        assertThat(exists("p.Kept")).isTrue();
    }

    @Test
    void parentIsDeletedOneRunAfterItsLastChild() {
        long parent = symbol("p.Owner", null);
        declaration(symbol("p.Owner#run()", parent));
        jdbc.update("DELETE FROM symbol_declaration");

        assertThat(cleanup.deleteOrphans()).isEqualTo(1);
        assertThat(exists("p.Owner#run()")).isFalse();
        assertThat(exists("p.Owner")).isTrue();

        assertThat(cleanup.deleteOrphans()).isEqualTo(1);
        assertThat(exists("p.Owner")).isFalse();
    }

    @Autowired
    AppSettings settings;

    @Test
    void deletesInBatchesUntilNoFullBatchIsLeft() {
        symbol("p.A", null);
        symbol("p.B", null);
        symbol("p.C", null);
        SettingsOverride overrides = new SettingsOverride(settings).set(SettingKeys.CLEANUP_BATCH_SIZE, "1");
        try {
            assertThat(cleanup.deleteOrphans()).isEqualTo(3);
        } finally {
            overrides.restore();
        }
        assertThat(exists("p.A") || exists("p.B") || exists("p.C")).isFalse();
    }
}
