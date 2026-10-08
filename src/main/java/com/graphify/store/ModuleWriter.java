package com.graphify.store;

import com.graphify.common.util.Utf8;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Makes a repository's {@code maven_module} rows match the given modules. Caller removes their usages and declarations first; dependency rows are replaced here. */
@Component
class ModuleWriter {

    private static final String MERGE = """
            MERGE INTO maven_module m
            USING (SELECT ? AS repo_id, ? AS path, ? AS group_id, ? AS artifact_id, ? AS version,
                          ? AS classpath_mode, ? AS packaging FROM dual) n
            ON (m.repo_id = n.repo_id AND m.path = n.path)
            WHEN MATCHED THEN UPDATE SET m.group_id = n.group_id, m.artifact_id = n.artifact_id,
                    m.version = n.version, m.classpath_mode = n.classpath_mode, m.packaging = n.packaging
            WHEN NOT MATCHED THEN INSERT (repo_id, path, group_id, artifact_id, version, classpath_mode, packaging)
                VALUES (n.repo_id, n.path, n.group_id, n.artifact_id, n.version, n.classpath_mode, n.packaging)
            """;

    private final JdbcTemplate jdbc;

    ModuleWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String cut(String text, int maxBytes) {
        return text == null ? null : Utf8.truncateToBytes(text, maxBytes);
    }

    /** Returns module path → id for the given modules after removing the repository's other modules. */
    Map<String, Long> replace(long repositoryId, List<ModuleRecord> modules) {
        jdbc.update("DELETE FROM module_dependency WHERE module_id IN (SELECT id FROM maven_module WHERE repo_id = ?)",
                repositoryId);
        Set<String> wanted = modules.stream().map(ModuleRecord::path).collect(Collectors.toSet());
        List<Object[]> stale = jdbc.query("SELECT id, path FROM maven_module WHERE repo_id = ?",
                        (rs, row) -> new Object[] {rs.getLong("id"), rs.getString("path")}, repositoryId)
                .stream()
                .filter(row -> !wanted.contains((String) row[1]))
                .map(row -> new Object[] {row[0]})
                .toList();
        jdbc.batchUpdate("DELETE FROM maven_module WHERE id = ?", stale);
        jdbc.batchUpdate(MERGE, modules.stream().map(module -> new Object[] {
                repositoryId, module.path(), cut(module.groupId(), StoreLimits.GROUP_ID_BYTES),
                cut(module.artifactId(), StoreLimits.ARTIFACT_ID_BYTES),
                cut(module.version(), StoreLimits.VERSION_BYTES), module.classpathMode().name(),
                cut(module.packaging(), StoreLimits.PACKAGING_BYTES)}).toList());
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, path FROM maven_module WHERE repo_id = ?",
                rs -> {
                    ids.put(rs.getString("path"), rs.getLong("id"));
                },
                repositoryId);
        List<Object[]> dependencies = new java.util.ArrayList<>();
        for (ModuleRecord module : modules) {
            for (DependencyRecord dependency : module.dependencies()) {
                // Oracle stores '' as NULL, so a blank coordinate would hit NOT NULL; a blank scope means the default
                if (dependency.groupId() == null || dependency.groupId().isBlank() || dependency.artifactId() == null
                        || dependency.artifactId().isBlank()
                        || !StoreLimits.fits(dependency.groupId(), StoreLimits.GROUP_ID_BYTES)
                        || !StoreLimits.fits(dependency.artifactId(), StoreLimits.ARTIFACT_ID_BYTES)) {
                    continue;
                }
                dependencies.add(new Object[] {ids.get(module.path()), dependency.groupId(), dependency.artifactId(),
                        cut(dependency.version(), StoreLimits.VERSION_BYTES),
                        cut(dependency.scope() == null || dependency.scope().isBlank() ? "compile" : dependency.scope(),
                                StoreLimits.SCOPE_BYTES)});
            }
        }
        jdbc.batchUpdate("INSERT INTO module_dependency (module_id, group_id, artifact_id, version, scope) "
                + "VALUES (?, ?, ?, ?, ?)", dependencies);
        return ids;
    }
}
