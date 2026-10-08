package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.graphify.indexer.model.UsageKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepoGraphAnalyzerTest {

    private static RepoGraphAnalyzer.Analysis analyze(long seed) {
        return RepoGraphAnalyzer.analyze(SampleGraphs.sample(), Set.of("com.g.web.Api"), seed, 100);
    }

    @Test
    void computesDegreesTransitiveDependentsAndEntryPoints() {
        Map<String, ClassMetrics> metrics = analyze(42).metrics();

        assertThat(metrics.get("com.g.a.AlphaHelper")).satisfies(m -> {
            assertThat(m.inDegree()).isEqualTo(2);
            assertThat(m.outDegree()).isZero();
            assertThat(m.dependents()).isEqualTo(5);
        });
        assertThat(metrics.get("com.g.b.Beta").dependents()).isEqualTo(4);
        assertThat(metrics.get("com.g.a.Alpha").dependents()).isEqualTo(3);
        assertThat(metrics.get("com.g.c.Gamma").dependents()).isEqualTo(2);
        assertThat(metrics.get("com.g.c.Gamma").inDegree()).isEqualTo(2);
        assertThat(metrics.get("com.g.web.Api").dependents()).isZero();
        assertThat(metrics.get("com.g.web.Api").outDegree()).isEqualTo(2);
        assertThat(metrics).allSatisfy((fqn, m) -> assertThat(m.entryPoint()).isEqualTo(fqn.equals("com.g.web.Api")));
    }

    @Test
    void findsThePackageCycle() {
        assertThat(analyze(42).cycles()).containsExactly(List.of("com.g.a", "com.g.b"));
    }

    @Test
    void communitiesCoverEveryClassAndAreStableForASeed() {
        RepoGraphAnalyzer.Analysis first = analyze(42);

        assertThat(first.metrics()).hasSize(7).allSatisfy((fqn, m) -> {
            assertThat(m.communityId()).isPositive();
            assertThat(m.communityLabel()).isIn("com.g.a", "com.g.b", "com.g.c", "com.g.web");
        });
        List<Integer> ids = first.metrics().values().stream().map(ClassMetrics::communityId).distinct().sorted()
                .toList();
        assertThat(ids.getFirst()).isEqualTo(1);
        assertThat(ids.getLast()).isEqualTo(ids.size());
        assertThat(analyze(42).metrics()).isEqualTo(first.metrics());
    }

    @Test
    void anEmptyRepositoryHasNoAnalyses() {
        RepoGraphAnalyzer.Analysis empty = RepoGraphAnalyzer.analyze(
                new ClassGraph(1, Map.of(), List.of(), List.of()), Set.of(), 42, 100);

        assertThat(empty.metrics()).isEmpty();
        assertThat(empty.cycles()).isEmpty();
    }

    @Test
    void defaultPackageIsNamedInCycles() {
        ClassGraph graph = graph(List.of("A", "p.B"), "A>p.B", "p.B>A");

        assertThat(RepoGraphAnalyzer.analyze(graph, Set.of(), 42, 100).cycles())
                .containsExactly(List.of("(default package)", "p"));
    }

    private static ClassGraph graph(List<String> fqns, String... edges) {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        long id = 1;
        for (String fqn : fqns) {
            classes.put(fqn, new ClassNode(id++, fqn, ClassGraph.packageOf(fqn), "m"));
        }
        List<ClassEdge> list = new ArrayList<>();
        for (String edge : edges) {
            String[] pair = edge.split(">");
            list.add(new ClassEdge(pair[0], pair[1], UsageKind.CALL, 1));
        }
        return new ClassGraph(1, classes, list, List.of());
    }

    @Test
    void numbersCommunitiesBySizeThenSmallestMemberAndLabelsThemByDominantPackage() {
        ClassGraph graph = graph(List.of("z.A", "z.B", "y.C", "b.X", "a.Y", "P", "Q"),
                "z.A>z.B", "z.B>y.C", "y.C>z.A", "b.X>a.Y", "P>Q");

        Map<String, ClassMetrics> metrics = RepoGraphAnalyzer.analyze(graph, Set.of(), 7, 100).metrics();

        assertThat(metrics.get("z.A").communityId()).isEqualTo(1);
        assertThat(metrics.get("y.C").communityId()).isEqualTo(1);
        assertThat(metrics.get("z.A").communityLabel()).isEqualTo("z");
        // equal sizes: the community with the smaller smallest member ("P" < "a.Y") comes first
        assertThat(metrics.get("P").communityId()).isEqualTo(2);
        assertThat(metrics.get("Q").communityLabel()).isEqualTo("(default package)");
        assertThat(metrics.get("a.Y").communityId()).isEqualTo(3);
        assertThat(metrics.get("b.X").communityId()).isEqualTo(3);
        // one class in each of two packages: the smaller package name wins
        assertThat(metrics.get("b.X").communityLabel()).isEqualTo("a");
    }

    @Test
    void communitiesSharingADominantPackageAreToldApartByTheirMostUsedClass() {
        ClassGraph graph = graph(List.of("p.A", "p.B", "p.C", "p.D", "p.E", "q.F", "q.G"),
                "p.A>p.B", "p.C>p.D", "p.E>p.D", "q.F>q.G");

        Map<String, ClassMetrics> metrics = RepoGraphAnalyzer.analyze(graph, Set.of(), 7, 100).metrics();

        // two communities whose dominant package is "p": each label names its most used class
        assertThat(metrics.get("p.A").communityLabel()).isEqualTo("p · B");
        assertThat(metrics.get("p.C").communityLabel()).isEqualTo("p · D");
        assertThat(metrics.get("p.A").communityId()).isNotEqualTo(metrics.get("p.C").communityId());
        // a package that labels only one community keeps the plain package name
        assertThat(metrics.get("q.F").communityLabel()).isEqualTo("q");
    }

    @Test
    void sameNamedClassesInSubPackagesStillGiveDistinctLabels() {
        ClassGraph graph = graph(List.of("p.A", "p.B", "p.x.Foo", "p.C", "p.D", "p.y.Foo"),
                "p.A>p.x.Foo", "p.B>p.x.Foo", "p.C>p.y.Foo", "p.D>p.y.Foo");

        Map<String, ClassMetrics> metrics = RepoGraphAnalyzer.analyze(graph, Set.of(), 7, 100).metrics();

        // the most used class is named relative to the shared package, so the two Foo's are told apart
        assertThat(metrics.get("p.A").communityLabel()).isEqualTo("p · x.Foo");
        assertThat(metrics.get("p.C").communityLabel()).isEqualTo("p · y.Foo");
    }

    @Test
    void computesExactDependentsOnALargeGraphQuickly() {
        List<String> fqns = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            fqns.add("p.C%04d".formatted(i));
            if (i > 0) {
                edges.add("p.C%04d>p.C%04d".formatted(i - 1, i));
            }
        }
        for (int i = 0; i < 500; i++) {
            fqns.add("q.K%03d".formatted(i));
            edges.add("q.K%03d>q.K%03d".formatted(i, (i + 1) % 500));
        }
        edges.add("q.K000>p.C0000");
        ClassGraph graph = graph(fqns, edges.toArray(String[]::new));

        Map<String, ClassMetrics> metrics = assertTimeout(Duration.ofSeconds(5),
                () -> RepoGraphAnalyzer.analyze(graph, Set.of(), 1, 100).metrics());

        assertThat(metrics.get("q.K250").dependents()).isEqualTo(499);
        assertThat(metrics.get("p.C0000").dependents()).isEqualTo(500);
        assertThat(metrics.get("p.C0010").dependents()).isEqualTo(510);
        assertThat(metrics.get("p.C1999").dependents()).isEqualTo(2499);
    }
}
