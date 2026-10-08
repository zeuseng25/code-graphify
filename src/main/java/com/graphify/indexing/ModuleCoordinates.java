package com.graphify.indexing;

import com.graphify.maven.Gav;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Which active repositories of enabled connections last declared each module coordinate (maven_module), to find
 * providers outside a run.
 */
@Repository
public class ModuleCoordinates {

    private final JdbcTemplate jdbc;

    public ModuleCoordinates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Gav, List<Long>> declaringRepositories() {
        Map<Gav, List<Long>> declared = new LinkedHashMap<>();
        jdbc.query("""
                SELECT m.repo_id, m.group_id, m.artifact_id, m.version
                  FROM maven_module m JOIN scm_repository r ON r.id = m.repo_id
                  JOIN scm_connection c ON c.id = r.connection_id
                 WHERE r.active = 1 AND c.enabled = 1 AND m.group_id IS NOT NULL AND m.artifact_id IS NOT NULL AND m.version IS NOT NULL
                 ORDER BY r.project_key, r.slug, r.id
                """, rs -> {
                    Gav gav = Gav.of(rs.getString("group_id"), rs.getString("artifact_id"), rs.getString("version"));
                    if (gav != null) {
                        List<Long> repositories = declared.computeIfAbsent(gav, g -> new ArrayList<>());
                        long repositoryId = rs.getLong("repo_id");
                        if (!repositories.contains(repositoryId)) {
                            repositories.add(repositoryId);
                        }
                    }
                });
        return declared;
    }
}
