package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.maven.Gav;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The install phase's pure decisions: who re-indexes, which installs are remembered, and which installs of one layer
 * may run together.
 */
class IndexRunExecutorPlanningTest {

    private static final ArtifactInstallOutcome INSTALLED = new ArtifactInstallOutcome(ArtifactInstallStatus.INSTALLED,
            null);
    private static final ArtifactInstallOutcome UP_TO_DATE = new ArtifactInstallOutcome(
            ArtifactInstallStatus.UP_TO_DATE, null);
    private static final ArtifactInstallOutcome FAILED = new ArtifactInstallOutcome(ArtifactInstallStatus.FAILED, "x");

    @Test
    void aConsumerReindexesWhenAnyTransitiveProviderWasInstalled() {
        // 3 needs 2, 2 needs 1: only 1 was installed in this run
        Map<Long, Set<Long>> transitive = Map.of(3L, Set.of(2L, 1L), 2L, Set.of(1L));
        Map<Long, ArtifactInstallOutcome> installs = Map.of(1L, INSTALLED, 2L, UP_TO_DATE);

        assertThat(IndexRunExecutor.reindex(3L, transitive, installs)).isTrue();
        assertThat(IndexRunExecutor.reindex(2L, transitive, installs)).isTrue();
        assertThat(IndexRunExecutor.reindex(1L, transitive, installs)).isFalse();
    }

    @Test
    void upToDateFailedOrOwnInstallsDoNotReindex() {
        Map<Long, Set<Long>> transitive = Map.of(3L, Set.of(2L, 1L));

        assertThat(IndexRunExecutor.reindex(3L, transitive, Map.of(1L, FAILED, 2L, UP_TO_DATE, 3L, INSTALLED)))
                .isFalse();
        assertThat(IndexRunExecutor.reindex(4L, transitive, Map.of(1L, INSTALLED))).isFalse();
    }

    private static RepoIndexOutcome outcome(RepoIndexStatus status) {
        return new RepoIndexOutcome(status, "c", null, null, 0, 0, 0, 0);
    }

    @Test
    void anInstallIsRememberedOnlyWhenEveryConsumerInTheRunWasIndexedWithoutFailing() {
        // 3 needs 2 and (through 2) 1; 4 needs 1; 5 is in the run but needs nothing
        Map<Long, Set<Long>> transitive = Map.of(3L, Set.of(2L, 1L), 2L, Set.of(1L), 4L, Set.of(1L));
        Map<Long, String> installed = Map.of(1L, "c1", 2L, "c2");
        Set<Long> inRun = Set.of(2L, 3L, 4L, 5L);

        assertThat(IndexRunExecutor.installedToRecord(installed, transitive, inRun, Map.of(
                2L, outcome(RepoIndexStatus.SUCCESS), 3L, outcome(RepoIndexStatus.SUCCESS_PARTIAL),
                4L, outcome(RepoIndexStatus.SKIPPED_NOT_JAVA), 5L, outcome(RepoIndexStatus.FAILED))))
                .isEqualTo(Map.of(1L, "c1", 2L, "c2"));
        // 4 failed: 1 is not remembered, 2 (not needed by 4) is
        assertThat(recorded(installed, transitive, inRun, Map.of(
                2L, outcome(RepoIndexStatus.SUCCESS), 3L, outcome(RepoIndexStatus.SUCCESS),
                4L, outcome(RepoIndexStatus.FAILED)))).isEqualTo(Map.of(2L, "c2"));
        // 3 has no outcome (cancelled or interrupted): neither is remembered
        assertThat(recorded(installed, transitive, inRun, Map.of(2L, outcome(RepoIndexStatus.SUCCESS),
                4L, outcome(RepoIndexStatus.SUCCESS)))).isEmpty();
        // a provider no consumer of the run needs (a self-install, or consumers outside the run) is remembered
        assertThat(recorded(Map.of(9L, "c9"), transitive, inRun, Map.of())).isEqualTo(Map.of(9L, "c9"));
    }

    private static Map<Long, String> recorded(Map<Long, String> installed, Map<Long, Set<Long>> transitive,
            Set<Long> inRun, Map<Long, RepoIndexOutcome> outcomes) {
        return IndexRunExecutor.installedToRecord(installed, transitive, inRun, outcomes);
    }

    @Test
    void installsThatBuildTheSameCoordinateRunOneAfterTheOther() {
        Gav a = Gav.of("g", "a", "1");
        Gav b = Gav.of("g", "b", "1");
        Gav c = Gav.of("g", "c", "1");
        Map<Long, Set<Gav>> built = Map.of(1L, Set.of(a), 2L, Set.of(b), 3L, Set.of(a, c), 4L, Set.of(c), 5L, Set.of());

        assertThat(IndexRunExecutor.installGroups(List.of(1L, 2L, 3L, 4L, 5L), built, false))
                .containsExactly(List.of(1L, 3L, 4L), List.of(2L), List.of(5L));
    }

    @Test
    void theMembersOfACycleInstallOneAfterTheOther() {
        Map<Long, Set<Gav>> built = Map.of(7L, Set.of(Gav.of("g", "x", "1")), 8L, Set.of(Gav.of("g", "y", "1")));

        assertThat(IndexRunExecutor.installGroups(List.of(7L, 8L), built, true)).containsExactly(List.of(7L, 8L));
    }
}
