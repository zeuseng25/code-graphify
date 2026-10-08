package com.graphify.impact;

import com.graphify.store.Chunks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Spec §5.6 version difference: an affected module declares a dependency on the changed code's artifact at a version
 * other than the indexed one, so the analysis may not match what that module actually compiles against. The declared
 * side is every module that declares a target or its parent symbol; dependencies without a resolved version are
 * ignored.
 */
@Component
public class VersionWarnings {

    private record Coordinates(String groupId, String artifactId) {

        String text() {
            return groupId + ":" + artifactId;
        }
    }

    private final JdbcTemplate jdbc;

    public VersionWarnings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<VersionWarning> find(Collection<Long> targetIds, List<RepoState> affectedModules) {
        if (targetIds.isEmpty() || affectedModules.isEmpty()) {
            return List.of();
        }
        Map<Coordinates, Set<String>> declared = declaredVersions(List.copyOf(targetIds));
        if (declared.isEmpty()) {
            return List.of();
        }
        Map<Long, RepoState> modules = new LinkedHashMap<>();
        affectedModules.forEach(state -> modules.put(state.moduleId(), state));
        List<VersionWarning> warnings = new ArrayList<>();
        for (List<Long> chunk : Chunks.of(List.copyOf(modules.keySet()), Chunks.MAX_IN_LIST)) {
            jdbc.query("SELECT module_id, group_id, artifact_id, version FROM module_dependency "
                    + "WHERE version IS NOT NULL AND module_id IN (" + Chunks.placeholders(chunk.size()) + ")", rs -> {
                        Coordinates coordinates = new Coordinates(rs.getString("group_id"), rs.getString("artifact_id"));
                        Set<String> versions = declared.get(coordinates);
                        String used = rs.getString("version");
                        if (versions != null && !versions.contains(used)) {
                            RepoState state = modules.get(rs.getLong("module_id"));
                            warnings.add(new VersionWarning(state.repositoryRef(), state.modulePath(),
                                    coordinates.text(), used, String.join(", ", versions)));
                        }
                    }, chunk.toArray());
        }
        warnings.sort(Comparator.comparing(VersionWarning::repository, RepoState.REPOSITORY_ORDER)
                .thenComparing(VersionWarning::modulePath).thenComparing(VersionWarning::dependency));
        return warnings;
    }

    private Map<Coordinates, Set<String>> declaredVersions(List<Long> targetIds) {
        Map<Coordinates, Set<String>> declared = new LinkedHashMap<>();
        for (List<Long> chunk : Chunks.of(targetIds, Chunks.MAX_IN_LIST)) {
            String in = Chunks.placeholders(chunk.size());
            List<Object> args = new ArrayList<>(chunk);
            args.addAll(chunk);
            jdbc.query("""
                    SELECT DISTINCT m.group_id, m.artifact_id, m.version
                      FROM symbol_declaration d JOIN maven_module m ON m.id = d.module_id
                     WHERE m.group_id IS NOT NULL AND m.artifact_id IS NOT NULL AND m.version IS NOT NULL
                       AND d.symbol_id IN (SELECT id FROM symbol WHERE id IN (%s)
                                           UNION SELECT parent_id FROM symbol WHERE id IN (%s) AND parent_id IS NOT NULL)
                    """.formatted(in, in), rs -> {
                        declared.computeIfAbsent(new Coordinates(rs.getString("group_id"), rs.getString("artifact_id")),
                                k -> new TreeSet<>()).add(rs.getString("version"));
                    }, args.toArray());
        }
        return declared;
    }
}
