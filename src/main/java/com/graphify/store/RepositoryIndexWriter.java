package com.graphify.store;

import com.graphify.common.util.Utf8;
import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.Usage;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces one repository's modules, declarations and usages with a new index result in a single transaction.
 * If anything fails, the previous index stays as it was (spec §8).
 */
@Service
public class RepositoryIndexWriter {

    private static final String INSERT_DECLARATION =
            "INSERT INTO symbol_declaration (symbol_id, module_id, file_path, line_no) VALUES (?, ?, ?, ?)";

    private static final String INSERT_USAGE = """
            INSERT INTO usage (from_symbol_id, to_symbol_id, module_id, kind, confidence, file_path, line_no,
                               column_no, snippet)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final SymbolWriter symbols;
    private final ModuleWriter modules;
    private final AppSettings settings;

    public RepositoryIndexWriter(JdbcTemplate jdbc, SymbolWriter symbols, ModuleWriter modules, AppSettings settings) {
        this.jdbc = jdbc;
        this.symbols = symbols;
        this.modules = modules;
        this.settings = settings;
    }

    @Transactional
    public WriteSummary replace(RepositoryIndex index) {
        validate(index);
        long repositoryId = index.repositoryId();
        lock(repositoryId);
        jdbc.update("DELETE FROM usage WHERE module_id IN (SELECT id FROM maven_module WHERE repo_id = ?)",
                repositoryId);
        jdbc.update("DELETE FROM symbol_declaration WHERE module_id IN "
                + "(SELECT id FROM maven_module WHERE repo_id = ?)", repositoryId);
        Map<String, Long> moduleIds = modules.replace(repositoryId, index.modules());

        int batchSize = settings.getInt(SettingKeys.STORE_JDBC_BATCH_SIZE);
        IndexResult result = index.result();
        SymbolWriter.Result stored = symbols.upsert(result.symbols(), batchSize,
                settings.getInt(SettingKeys.INDEX_PARALLELISM));
        Map<String, Long> ids = stored.ids();

        int skippedRows = 0;
        List<Object[]> declarations = new ArrayList<>();
        for (Declaration declaration : result.declarations()) {
            Long symbolId = ids.get(declaration.symbolKey());
            if (symbolId == null || !StoreLimits.fits(declaration.filePath(), StoreLimits.FILE_PATH_BYTES)) {
                skippedRows++;
                continue;
            }
            declarations.add(new Object[] {symbolId, moduleIds.get(declaration.modulePath()),
                    declaration.filePath(), declaration.line()});
        }
        List<Object[]> usages = new ArrayList<>();
        for (Usage usage : result.usages()) {
            Long fromId = ids.get(usage.fromKey());
            Long toId = ids.get(usage.toKey());
            if (fromId == null || toId == null || !StoreLimits.fits(usage.filePath(), StoreLimits.FILE_PATH_BYTES)) {
                skippedRows++;
                continue;
            }
            usages.add(new Object[] {fromId, toId, moduleIds.get(usage.modulePath()), usage.kind().name(),
                    usage.confidence().name(), usage.filePath(), usage.line(), usage.column(),
                    usage.snippet() == null ? null : Utf8.truncateToBytes(usage.snippet(), StoreLimits.SNIPPET_BYTES)});
        }
        for (List<Object[]> chunk : Chunks.of(declarations, batchSize)) {
            jdbc.batchUpdate(INSERT_DECLARATION, chunk);
        }
        for (List<Object[]> chunk : Chunks.of(usages, batchSize)) {
            jdbc.batchUpdate(INSERT_USAGE, chunk);
        }
        jdbc.update("UPDATE scm_repository SET last_indexed_commit = ?, last_indexed_at = SYSTIMESTAMP WHERE id = ?",
                index.commit(), repositoryId);
        return new WriteSummary(ids.size(), declarations.size(), usages.size(), stored.skippedKeys().size(),
                skippedRows);
    }

    private void lock(long repositoryId) {
        List<Long> found = jdbc.queryForList("SELECT id FROM scm_repository WHERE id = ? FOR UPDATE", Long.class,
                repositoryId);
        if (found.isEmpty()) {
            throw new RepositoryNotFoundException(repositoryId);
        }
    }

    private static void validate(RepositoryIndex index) {
        if (index.commit() == null || index.commit().isBlank()) {
            throw new IllegalArgumentException("commit must not be blank");
        }
        if (!StoreLimits.fits(index.commit(), StoreLimits.COMMIT_BYTES)) {
            throw new IllegalArgumentException("commit exceeds " + StoreLimits.COMMIT_BYTES + " bytes");
        }
        Set<String> modulePaths = new HashSet<>();
        for (ModuleRecord module : index.modules()) {
            if (module.path() == null || module.path().isBlank()) {
                throw new IllegalArgumentException("module path must not be blank; use \".\" for the root module");
            }
            if (!StoreLimits.fits(module.path(), StoreLimits.MODULE_PATH_BYTES)) {
                throw new IllegalArgumentException("module path exceeds " + StoreLimits.MODULE_PATH_BYTES + " bytes");
            }
            if (module.classpathMode() == null) {
                throw new IllegalArgumentException("module " + module.path() + " has no classpath mode");
            }
            if (!modulePaths.add(module.path())) {
                throw new IllegalArgumentException("duplicate module path " + module.path());
            }
        }
        Set<String> symbolKeys = new HashSet<>();
        for (Symbol symbol : index.result().symbols()) {
            symbolKeys.add(symbol.key());
        }
        for (Declaration declaration : index.result().declarations()) {
            requireModule(modulePaths, declaration.modulePath());
            requireSymbol(symbolKeys, declaration.symbolKey());
        }
        for (Usage usage : index.result().usages()) {
            requireModule(modulePaths, usage.modulePath());
            requireSymbol(symbolKeys, usage.fromKey());
            requireSymbol(symbolKeys, usage.toKey());
        }
    }

    private static void requireModule(Set<String> modulePaths, String modulePath) {
        if (!modulePaths.contains(modulePath)) {
            throw new IllegalArgumentException("index result references module " + modulePath
                    + " which is not in the module list " + modulePaths);
        }
    }

    private static void requireSymbol(Set<String> symbolKeys, String key) {
        if (!symbolKeys.contains(key)) {
            throw new IllegalArgumentException("index result references unknown symbol " + key);
        }
    }

    /** Deletes a repository's index rows (spec §4.1: a repository gone from SCM); keeps the repository and its run history. */
    @Transactional
    public void remove(long repositoryId) {
        lock(repositoryId);
        // keep the graph table list in sync with RepoGraphStore.clear
        for (String table : List.of("repo_graph_cycle", "repo_graph_community", "repo_graph_metric",
                "repo_graph_analysis")) {
            jdbc.update("DELETE FROM " + table + " WHERE repo_id = ?", repositoryId);
        }
        String modules = "(SELECT id FROM maven_module WHERE repo_id = ?)";
        jdbc.update("DELETE FROM usage WHERE module_id IN " + modules, repositoryId);
        jdbc.update("DELETE FROM symbol_declaration WHERE module_id IN " + modules, repositoryId);
        jdbc.update("DELETE FROM module_dependency WHERE module_id IN " + modules, repositoryId);
        jdbc.update("DELETE FROM maven_module WHERE repo_id = ?", repositoryId);
        jdbc.update("UPDATE scm_repository SET last_indexed_commit = NULL, last_indexed_at = NULL WHERE id = ?",
                repositoryId);
    }
}
