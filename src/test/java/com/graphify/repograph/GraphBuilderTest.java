package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphBuilderTest {

    private final GraphBuilder builder = new GraphBuilder(SampleGraphs.sample(),
            Map.of(2L, new ClassMetrics(2, 0, 5, false, 1, "com.g.a")));

    private static List<String> ids(RepoGraph graph) {
        return graph.nodes().stream().map(GraphNode::id).toList();
    }

    private static List<String> edges(RepoGraph graph) {
        return graph.edges().stream().map(e -> e.from() + ">" + e.to()).toList();
    }

    @Test
    void packagesAndModulesAggregateUsagesBetweenThem() {
        RepoGraph packages = builder.build(GraphLevel.PACKAGE, null, null, 500);

        // the sample carries one external library, so it is part of every view
        assertThat(ids(packages)).containsExactly("external:lib:com.lib:util:1.0", "package:com.g.a",
                "package:com.g.b", "package:com.g.c", "package:com.g.web");
        assertThat(edges(packages)).containsExactly("package:com.g.a>external:lib:com.lib:util:1.0",
                "package:com.g.a>package:com.g.b", "package:com.g.b>external:lib:com.lib:util:1.0",
                "package:com.g.b>package:com.g.a", "package:com.g.c>package:com.g.a", "package:com.g.web>package:com.g.c");
        GraphEdge aToB = packages.edges().get(1);
        assertThat(aToB.weight()).isEqualTo(3);
        assertThat(aToB.kinds()).containsEntry(UsageKind.TYPE_REF, 1L).containsEntry(UsageKind.INSTANTIATION, 1L)
                .containsEntry(UsageKind.CALL, 1L);
        assertThat(packages.truncated()).isFalse();

        RepoGraph modules = builder.build(GraphLevel.MODULE, "ignored", null, 500);
        assertThat(ids(modules)).containsExactly("external:lib:com.lib:util:1.0", "module:app", "module:core");
        assertThat(edges(modules)).containsExactly("module:app>module:core",
                "module:core>external:lib:com.lib:util:1.0");
        assertThat(modules.focus()).isNull();
    }

    @Test
    void aFocusKeepsClassesUnderThePackagePrefixAndCarriesStoredMetrics() {
        RepoGraph classes = builder.build(GraphLevel.CLASS, "com.g.a", null, 500);

        assertThat(ids(classes)).containsExactly("class:com.g.a.Alpha", "class:com.g.a.AlphaHelper",
                "external:lib:com.lib:util:1.0");
        assertThat(edges(classes)).containsExactly("class:com.g.a.Alpha>class:com.g.a.AlphaHelper",
                "class:com.g.a.Alpha>external:lib:com.lib:util:1.0");
        assertThat(classes.nodes().get(1).metrics().dependents()).isEqualTo(5);
        assertThat(classes.nodes().getFirst().metrics()).isNull();
        assertThat(ids(builder.build(GraphLevel.CLASS, "com.g", null, 500))).hasSize(8);
    }

    @Test
    void rollsUpOneLevelAtATimeUntilTheGraphFits() {
        RepoGraph toPackages = builder.build(GraphLevel.CLASS, null, null, 5);
        assertThat(toPackages.level()).isEqualTo(GraphLevel.PACKAGE);
        assertThat(toPackages.requestedLevel()).isEqualTo(GraphLevel.CLASS);
        assertThat(toPackages.truncated()).isTrue();
        assertThat(toPackages.suggestion()).contains("8 class nodes exceed the node limit (5)");

        RepoGraph toModules = builder.build(GraphLevel.CLASS, null, null, 3);
        assertThat(toModules.level()).isEqualTo(GraphLevel.MODULE);
        assertThat(ids(toModules)).hasSize(3);

        RepoGraph keepsFocus = builder.build(GraphLevel.CLASS, "com.g.a", null, 2);
        assertThat(keepsFocus.level()).isEqualTo(GraphLevel.PACKAGE);
        assertThat(keepsFocus.focus()).isEqualTo("com.g.a");
        assertThat(ids(keepsFocus)).containsExactly("external:lib:com.lib:util:1.0", "package:com.g.a");
    }

    @Test
    void externalTypesAreGroupedPerSource() {
        RepoGraph packages = builder.build(GraphLevel.PACKAGE, null, null, 500);

        assertThat(ids(packages)).contains("external:lib:com.lib:util:1.0");
        assertThat(packages.nodes()).filteredOn(n -> n.type() == NodeType.EXTERNAL_LIBRARY).singleElement()
                .satisfies(n -> assertThat(n.size()).isEqualTo(2));
        assertThat(edges(packages)).contains("package:com.g.a>external:lib:com.lib:util:1.0",
                "package:com.g.b>external:lib:com.lib:util:1.0");
    }

    @Test
    void theMethodLevelShowsAClassMembersWithTheirNeighbours() {
        MemberRef run = new MemberRef(11, "com.g.a.Alpha#run()", "com.g.a.Alpha", SymbolKind.METHOD, "int run()");
        MemberRef one = new MemberRef(12, "com.g.a.AlphaHelper#one()", "com.g.a.AlphaHelper", SymbolKind.METHOD,
                "int one()");
        MemberRef total = new MemberRef(13, "com.g.c.Gamma#total()", "com.g.c.Gamma", SymbolKind.METHOD,
                "int total()");
        MemberRef strings = new MemberRef(14, "com.lib.Strings#trim()", "com.lib.Strings", SymbolKind.METHOD,
                "String trim()");
        MemberGraph members = new MemberGraph(List.of(run), List.of(
                new MemberUse(run, one, UsageKind.CALL, 1), new MemberUse(total, run, UsageKind.CALL, 1),
                new MemberUse(run, strings, UsageKind.CALL, 2)));

        RepoGraph method = builder.build(GraphLevel.METHOD, "com.g.a.Alpha", members, 500);
        assertThat(ids(method)).containsExactly("external:lib:com.lib:util:1.0", "member:com.g.a.Alpha#run()",
                "member:com.g.a.AlphaHelper#one()", "member:com.g.c.Gamma#total()");
        assertThat(edges(method)).contains("member:com.g.a.Alpha#run()>member:com.g.a.AlphaHelper#one()",
                "member:com.g.c.Gamma#total()>member:com.g.a.Alpha#run()",
                "member:com.g.a.Alpha#run()>external:lib:com.lib:util:1.0");

        RepoGraph rolledUp = builder.build(GraphLevel.METHOD, "com.g.a.Alpha", members, 3);
        assertThat(rolledUp.level()).isEqualTo(GraphLevel.CLASS);
        assertThat(rolledUp.focus()).isEqualTo("com.g.a");
    }

    @Test
    void aDefaultPackageFocusRollsUpWithTheDefaultPackageLabel() {
        ClassGraph graph = new ClassGraph(1, Map.of("Top", new ClassNode(1, "Top", "", "app"),
                "Other", new ClassNode(2, "Other", "", "app")), List.of(), List.of());
        MemberRef run = new MemberRef(11, "Top#run()", "Top", SymbolKind.METHOD, "void run()");
        MemberRef other = new MemberRef(12, "Other#x()", "Other", SymbolKind.METHOD, "void x()");
        MemberGraph members = new MemberGraph(List.of(run),
                List.of(new MemberUse(run, other, UsageKind.CALL, 1)));

        RepoGraph rolled = new GraphBuilder(graph, Map.of()).build(GraphLevel.METHOD, "Top", members, 1);

        assertThat(rolled.level()).isEqualTo(GraphLevel.PACKAGE);
        assertThat(rolled.focus()).isEqualTo(RepoGraphAnalyzer.DEFAULT_PACKAGE);
    }

    @Test
    void theFullGraphIsOnlyLoadedWhenAViewRollsUp() {
        int[] loads = {0};
        GraphBuilder lazy = new GraphBuilder(SampleGraphs.sample(), () -> {
            loads[0]++;
            return SampleGraphs.sample();
        }, Map.of());
        MemberRef run = new MemberRef(11, "com.g.a.Alpha#run()", "com.g.a.Alpha", SymbolKind.METHOD, "int run()");
        MemberGraph members = new MemberGraph(List.of(run), List.of());

        lazy.build(GraphLevel.METHOD, "com.g.a.Alpha", members, 500);
        assertThat(loads[0]).isZero();
        lazy.build(GraphLevel.METHOD, "com.g.a.Alpha", members, 0);
        assertThat(loads[0]).isEqualTo(1);
    }
}
