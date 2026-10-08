package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.util.Utf8;
import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class RepositoryIndexWriterTest extends OracleIntegrationTest {

    private static final Path CORP_REPO = fixture();
    private static final List<ModuleRecord> MODULES = List.of(
            new ModuleRecord("common-lib", "com.corp", "common-lib", "1.0.0", ClasspathMode.NONE),
            new ModuleRecord("order-service", "com.corp", "order-service", "1.0.0", ClasspathMode.NONE));

    @Autowired
    RepositoryIndexWriter writer;

    private long repoId;
    private IndexResult corp;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        repoId = StoreFixtures.newRepository(jdbc, "corp-repo");
        corp = new JavaRepositoryIndexer().index(new IndexRequest(CORP_REPO,
                List.of(module("common-lib"), module("order-service")), new IndexerOptions(50, 300)));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void storesTheWholeIndexAndTheCommit() {
        WriteSummary summary = writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));

        assertThat(summary.usages()).isEqualTo(corp.usages().size()).isEqualTo(count("usage"));
        assertThat(summary.declarations()).isEqualTo(corp.declarations().size()).isEqualTo(count("symbol_declaration"));
        assertThat(summary.symbols()).isEqualTo(corp.symbols().size()).isEqualTo(count("symbol"));
        assertThat(summary.skippedSymbols()).isZero();
        assertThat(summary.skippedRows()).isZero();
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE id = ?", String.class,
                repoId)).isEqualTo("abc123");
        Map<String, Object> call = jdbc.queryForMap("""
                SELECT f.symbol_key AS from_key, u.confidence, u.line_no, m.path AS module_path, u.snippet
                  FROM usage u
                  JOIN symbol t ON t.id = u.to_symbol_id
                  JOIN symbol f ON f.id = u.from_symbol_id
                  JOIN maven_module m ON m.id = u.module_id
                 WHERE t.symbol_key = 'com.corp.common.MoneyUtil#format(int)' AND u.kind = 'CALL' AND u.line_no = 18
                """);
        assertThat(call).containsEntry("FROM_KEY", "com.corp.order.OrderService#b()")
                .containsEntry("CONFIDENCE", Confidence.EXACT.name())
                .containsEntry("MODULE_PATH", "order-service")
                .containsEntry("SNIPPET", "public void b() { money.format(5); }");
    }

    @Test
    void rewritingReplacesInsteadOfDuplicating() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));
        int usages = count("usage");
        int declarations = count("symbol_declaration");

        writer.replace(new RepositoryIndex(repoId, "def456", MODULES, corp));

        assertThat(count("usage")).isEqualTo(usages);
        assertThat(count("symbol_declaration")).isEqualTo(declarations);
        assertThat(count("maven_module")).isEqualTo(2);
    }

    @Test
    void droppingAModuleRemovesItsRowsAndTheModule() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));
        IndexResult onlyCommon = new JavaRepositoryIndexer().index(new IndexRequest(CORP_REPO,
                List.of(module("common-lib")), new IndexerOptions(50, 300)));

        writer.replace(new RepositoryIndex(repoId, "def456", List.of(MODULES.getFirst()), onlyCommon));

        assertThat(jdbc.queryForList("SELECT path FROM maven_module", String.class)).containsExactly("common-lib");
        assertThat(count("usage")).isEqualTo(onlyCommon.usages().size());
    }

    @Test
    void failedWriteKeepsThePreviousIndex() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));
        int usages = count("usage");
        int declarations = count("symbol_declaration");
        Usage original = corp.usages().getFirst();
        Usage boom = new Usage(original.fromKey(), original.toKey(), original.kind(), original.confidence(),
                original.modulePath(), "boom.java", original.line(), original.column(), original.snippet());
        List<Usage> changed = new ArrayList<>(corp.usages());
        changed.set(0, boom);
        IndexResult failing = new IndexResult(corp.symbols(), corp.declarations(), changed, corp.warnings());
        jdbc.execute("CREATE OR REPLACE TRIGGER test_fail_usage BEFORE INSERT ON usage FOR EACH ROW "
                + "WHEN (NEW.file_path = 'boom.java') BEGIN RAISE_APPLICATION_ERROR(-20001, 'injected failure'); END;");
        try {
            assertThatThrownBy(() -> writer.replace(new RepositoryIndex(repoId, "def456", MODULES, failing)))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("injected failure");
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_usage");
        }

        assertThat(count("usage")).isEqualTo(usages);
        assertThat(count("symbol_declaration")).isEqualTo(declarations);
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE id = ?", String.class,
                repoId)).isEqualTo("abc123");
    }

    @Test
    void overlongModuleMetadataIsTruncatedNotFatal() {
        List<ModuleRecord> modules = List.of(
                new ModuleRecord("common-lib", "g".repeat(400), "common-lib", "ş".repeat(80), ClasspathMode.NONE),
                MODULES.get(1));

        writer.replace(new RepositoryIndex(repoId, "abc123", modules, corp));

        assertThat(jdbc.queryForObject("SELECT LENGTHB(group_id) FROM maven_module WHERE path = 'common-lib'",
                Integer.class)).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT LENGTHB(version) FROM maven_module WHERE path = 'common-lib'",
                Integer.class)).isLessThanOrEqualTo(100);
    }

    @Test
    void oversizedMultibyteSnippetIsTruncatedToTheColumnWidth() {
        Usage original = corp.usages().getFirst();
        Usage huge = new Usage(original.fromKey(), original.toKey(), original.kind(), original.confidence(),
                original.modulePath(), original.filePath(), original.line(), original.column(), "ş".repeat(2100));
        List<Usage> usages = new ArrayList<>(corp.usages());
        usages.set(0, huge);
        IndexResult withHugeSnippet = new IndexResult(corp.symbols(), corp.declarations(), usages, corp.warnings());

        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, withHugeSnippet));

        String stored = jdbc.queryForObject("SELECT MAX(snippet) KEEP (DENSE_RANK FIRST ORDER BY LENGTHB(snippet) DESC) "
                + "FROM usage", String.class);
        assertThat(Utf8.byteLength(stored)).isLessThanOrEqualTo(4000).isGreaterThan(3990);
    }

    @Test
    void rejectsInconsistentInputBeforeWriting() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));
        int usages = count("usage");
        Usage dangling = new Usage("p.Nope#x()", "p.Nope#y()", UsageKind.CALL, Confidence.EXACT, "common-lib",
                "f.java", 1, 1, "x");
        List<RepositoryIndex> bad = List.of(
                new RepositoryIndex(repoId, "def456", List.of(MODULES.getFirst()), corp),
                new RepositoryIndex(repoId, "def456",
                        List.of(new ModuleRecord(" ", null, null, null, ClasspathMode.NONE)), corp),
                new RepositoryIndex(repoId, " ", MODULES, corp),
                new RepositoryIndex(repoId, "c".repeat(65), MODULES, corp),
                new RepositoryIndex(repoId, "def456", List.of(
                        new ModuleRecord("p".repeat(1001), null, null, null, ClasspathMode.NONE)), corp),
                new RepositoryIndex(repoId, "def456", MODULES,
                        new IndexResult(corp.symbols(), corp.declarations(), List.of(dangling), List.of())));
        for (RepositoryIndex index : bad) {
            assertThatIllegalArgumentException().isThrownBy(() -> writer.replace(index));
            assertThat(count("usage")).isEqualTo(usages);
            assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE id = ?",
                    String.class, repoId)).isEqualTo("abc123");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> writer.replace(bad.getFirst()))
                .withMessageContaining("order-service");
        assertThatIllegalArgumentException().isThrownBy(() -> writer.replace(bad.getLast()))
                .withMessageContaining("p.Nope#x()");
    }

    @Test
    void unknownRepositoryIsReported() {
        assertThatThrownBy(() -> writer.replace(new RepositoryIndex(-1L, "abc123", MODULES, corp)))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void storesAndReplacesDeclaredDependencies() {
        List<ModuleRecord> withDeps = List.of(
                new ModuleRecord("common-lib", "com.corp", "common-lib", "1.0.0", ClasspathMode.FULL,
                        List.of(new DependencyRecord("org.slf4j", "slf4j-api", "2.0.17", "compile"))),
                new ModuleRecord("order-service", "com.corp", "order-service", "1.0.0", ClasspathMode.FULL,
                        List.of(new DependencyRecord("com.corp", "common-lib", "1.0.0", "compile"),
                                new DependencyRecord("org.junit.jupiter", "junit-jupiter", null, "test"))));
        writer.replace(new RepositoryIndex(repoId, "abc123", withDeps, corp));

        assertThat(jdbc.queryForList("""
                SELECT m.path || ':' || d.group_id || ':' || d.artifact_id || ':' || NVL(d.version, '-') || ':' || d.scope
                  FROM module_dependency d JOIN maven_module m ON m.id = d.module_id ORDER BY 1
                """, String.class)).containsExactly(
                "common-lib:org.slf4j:slf4j-api:2.0.17:compile",
                "order-service:com.corp:common-lib:1.0.0:compile",
                "order-service:org.junit.jupiter:junit-jupiter:-:test");

        writer.replace(new RepositoryIndex(repoId, "def456", List.of(withDeps.getFirst()),
                new com.graphify.indexer.JavaRepositoryIndexer().index(new com.graphify.indexer.IndexRequest(CORP_REPO,
                        List.of(module("common-lib")), new com.graphify.indexer.IndexerOptions(50, 300)))));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_dependency", Integer.class)).isEqualTo(1);
    }

    @Test
    void blankDependencyCoordinatesAreSkippedAndABlankScopeIsCompile() {
        List<ModuleRecord> withDeps = List.of(
                new ModuleRecord("common-lib", "com.corp", "common-lib", "1.0.0", ClasspathMode.FULL,
                        List.of(new DependencyRecord("", "slf4j-api", "2.0.17", "compile"),
                                new DependencyRecord("org.slf4j", " ", "2.0.17", "compile"),
                                new DependencyRecord("org.slf4j", "slf4j-api", "2.0.17", ""))),
                MODULES.getLast());

        writer.replace(new RepositoryIndex(repoId, "abc123", withDeps, corp));

        assertThat(jdbc.queryForList("SELECT d.group_id || ':' || d.artifact_id || ':' || d.scope FROM module_dependency d",
                String.class)).containsExactly("org.slf4j:slf4j-api:compile");
    }

    @Test
    void removeClearsTheIndexButKeepsTheRepository() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));

        writer.remove(repoId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol_declaration", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maven_module", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE id = ?", String.class, repoId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE id = ?", Integer.class, repoId))
                .isEqualTo(1);
    }

    private static ModuleSource module(String name) {
        return new ModuleSource(name, List.of(CORP_REPO.resolve(name).resolve("src/main/java")), List.of());
    }

    private static Path fixture() {
        try {
            return Path.of(RepositoryIndexWriterTest.class.getResource("/fixtures/corp-repo").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
