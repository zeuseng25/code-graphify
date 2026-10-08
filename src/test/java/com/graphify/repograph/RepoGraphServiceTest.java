package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.GraphFixture;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepoGraphServiceTest extends OracleIntegrationTest {

    @Autowired
    RepoGraphService graphs;

    @Autowired
    RepoGraphStore store;

    @Autowired
    RepositoryIndexWriter writer;

    private long repo;

    @BeforeEach
    void setUp() {
        repo = GraphFixture.load(jdbc, writer);
        jdbc.update("INSERT INTO entry_point_annotation (annotation_fqn, label, enabled) VALUES (?, 'Test endpoint', 1)",
                GraphFixture.ENTRY_ANNOTATION);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM entry_point_annotation WHERE annotation_fqn = ?", GraphFixture.ENTRY_ANNOTATION);
    }

    private Map<String, Integer> communityBySymbolKey() {
        Map<String, Integer> communities = new TreeMap<>();
        jdbc.query("SELECT s.symbol_key, c.community_id FROM repo_graph_community c JOIN symbol s ON s.id = c.symbol_id "
                + "WHERE c.repo_id = ?", rs -> {
                    communities.put(rs.getString(1), rs.getInt(2));
                }, repo);
        return communities;
    }

    @Test
    void analysesAreStoredAndStableForTheSameSeed() {
        graphs.analyze(repo, GraphFixture.COMMIT);

        assertThat(store.state(repo)).hasValueSatisfying(state -> {
            assertThat(state.commit()).isEqualTo(GraphFixture.COMMIT);
            assertThat(state.classCount()).isEqualTo(7);
            assertThat(state.cycleCount()).isEqualTo(1);
        });
        assertThat(store.critical(repo, 2)).extracting(CriticalClass::fqn, CriticalClass::dependents)
                .containsExactly(tuple("com.g.a.AlphaHelper", 5),
                        tuple("com.g.b.Beta", 4));
        assertThat(store.cycles(repo)).containsExactly(new PackageCycle(1, List.of("com.g.a", "com.g.b")));
        assertThat(store.entryPointClasses(repo, 10)).containsExactly("com.g.web.Api");
        assertThat(store.entryPointCount(repo)).isEqualTo(1);
        Map<String, Integer> first = communityBySymbolKey();
        assertThat(first).hasSize(7);

        graphs.analyze(repo, GraphFixture.COMMIT);

        assertThat(communityBySymbolKey()).isEqualTo(first);
        assertThat(store.metrics(repo)).hasSize(7);
    }

    @Test
    void removingTheIndexRemovesTheAnalyses() {
        graphs.analyze(repo, GraphFixture.COMMIT);

        writer.remove(repo);

        assertThat(store.state(repo)).isEmpty();
        assertThat(store.metrics(repo)).isEmpty();
        assertThat(store.cycles(repo)).isEmpty();
    }
}
