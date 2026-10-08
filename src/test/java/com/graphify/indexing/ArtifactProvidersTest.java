package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.maven.Gav;
import com.graphify.maven.MavenModule;
import com.graphify.store.DependencyRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactProvidersTest {

    private static MavenModule module(String path, String root, String artifactId, String packaging, Gav parent,
            List<Gav> imports, DependencyRecord... dependencies) {
        return new MavenModule(path, "com.acme", artifactId, "1.0-SNAPSHOT", List.of(), List.of(dependencies), true,
                packaging, parent, imports, root);
    }

    private static Gav acme(String artifactId) {
        return new Gav("com.acme", artifactId, "1.0-SNAPSHOT");
    }

    private static DependencyRecord on(String artifactId) {
        return new DependencyRecord("com.acme", artifactId, "1.0-SNAPSHOT", "compile");
    }

    @Test
    void aParentALibraryAndABomMakeTheirRepositoriesProvidersInDependencyOrder() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(3L, List.of(module(".", ".", "app", "jar", acme("parent"), List.of(acme("bom")), on("lib"),
                new DependencyRecord("org.slf4j", "slf4j-api", "2.0.9", "compile"))));
        read.put(2L, List.of(module(".", ".", "lib", "jar", acme("parent"), List.of())));
        read.put(1L, List.of(module(".", ".", "parent", "pom", null, List.of()),
                module("bom", ".", "bom", "pom", null, List.of())));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.providersOf().get(3L)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(plan.providersOf().get(2L)).containsExactly(1L);
        assertThat(plan.layers()).containsExactly(List.of(1L), List.of(2L));
        assertThat(plan.provisions().get(1L).consumed()).containsOnlyKeys(acme("parent"), acme("bom"))
                .containsEntry(acme("parent"), "pom");
        assertThat(plan.provisions().get(2L).consumed()).containsExactly(Map.entry(acme("lib"), "jar"));
        assertThat(plan.cyclic()).isEmpty();
        assertThat(ArtifactProviders.unmatched(read)).containsExactly(new Gav("org.slf4j", "slf4j-api", "2.0.9"));
    }

    @Test
    void rootsOfOneRepositoryAreProvidersOfEachOtherAndOrderedByNeed() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module("svc-b", "svc-b", "b", "jar", null, List.of(), on("a")),
                module("svc-a", "svc-a", "a", "jar", null, List.of())));
        read.put(2L, List.of(module(".", ".", "app", "jar", null, List.of(), on("b"))));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.providersOf()).containsOnlyKeys(2L);
        assertThat(plan.provisions().get(1L).roots()).containsExactly("svc-a", "svc-b");
    }

    @Test
    void aRepositoryWhoseOwnRootUsesAnotherRootProvidesItselfWithoutBeingItsOwnProvider() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module("svc-a", "svc-a", "a", "jar", null, List.of()),
                module("svc-b", "svc-b", "b", "jar", null, List.of(), on("a"))));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.provisions().get(1L).roots()).containsExactly("svc-a");
        assertThat(plan.providersOf()).isEmpty();
        assertThat(plan.layers()).containsExactly(List.of(1L));
    }

    @Test
    void referencesInsideOneProjectRootNeedNothing() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module(".", ".", "p", "pom", null, List.of()),
                module("c", ".", "c", "jar", acme("p"), List.of())));

        assertThat(ArtifactProviders.plan(read).provisions()).isEmpty();
        assertThat(ArtifactProviders.unmatched(read)).isEmpty();
    }

    @Test
    void aGavDeclaredInTheConsumersOwnRepositoryIsNotProvidedByAnother() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module(".", ".", "g", "jar", null, List.of())));
        read.put(2L, List.of(module(".", ".", "g", "jar", null, List.of(), on("g"))));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.provisions()).isEmpty();
        assertThat(plan.providersOf()).isEmpty();
    }

    @Test
    void theFirstRepositoryInCallerOrderWinsADuplicateGav() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(5L, List.of(module(".", ".", "g", "jar", null, List.of())));
        read.put(4L, List.of(module(".", ".", "g", "jar", null, List.of())));
        read.put(9L, List.of(module(".", ".", "app", "jar", null, List.of(), on("g"))));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.providersOf().get(9L)).containsExactly(5L);
        assertThat(plan.provisions()).containsOnlyKeys(5L);
    }

    @Test
    void aChainOfThreeIsLayeredAndTransitiveProvidersAreClosed() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(3L, List.of(module(".", ".", "c", "jar", null, List.of(), on("b"))));
        read.put(2L, List.of(module(".", ".", "b", "jar", null, List.of(), on("a"))));
        read.put(1L, List.of(module(".", ".", "a", "jar", null, List.of())));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.layers()).containsExactly(List.of(1L), List.of(2L));
        assertThat(plan.provisions()).containsOnlyKeys(1L, 2L);
        assertThat(plan.transitiveProvidersOf().get(3L)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(plan.transitiveProvidersOf().get(2L)).containsExactly(1L);
        assertThat(plan.provisions()).doesNotContainKey(3L);
    }

    @Test
    void providersInOneLayerKeepCallerOrder() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(7L, List.of(module(".", ".", "p7", "jar", null, List.of())));
        read.put(2L, List.of(module(".", ".", "p2", "jar", null, List.of())));
        read.put(5L, List.of(module(".", ".", "p5", "jar", null, List.of())));
        read.put(9L, List.of(module(".", ".", "app", "jar", null, List.of(), on("p5"), on("p2"), on("p7"))));

        assertThat(ArtifactProviders.plan(read).layers()).containsExactly(List.of(7L, 2L, 5L));
    }

    @Test
    void aDiamondHasNoDuplicates() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(3L, List.of(module(".", ".", "c", "jar", null, List.of(), on("a"), on("b"))));
        read.put(2L, List.of(module(".", ".", "b", "jar", null, List.of(), on("a"))));
        read.put(1L, List.of(module(".", ".", "a", "jar", null, List.of())));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.layers()).containsExactly(List.of(1L), List.of(2L));
        assertThat(plan.transitiveProvidersOf().get(3L)).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void unmatchedSkipsPartialReferencesAndNullRootMeansDot() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module(".", null, "lib", "jar", null, List.of())));
        read.put(2L, List.of(module(".", ".", "app", "jar", null, List.of(), on("lib"),
                new DependencyRecord("com.acme", "x", "${x.version}", "compile"),
                new DependencyRecord("com.acme", "y", null, "compile"))));

        assertThat(ArtifactProviders.unmatched(read)).isEmpty();
        assertThat(ArtifactProviders.plan(read).provisions().get(1L).roots()).containsExactly(".");
    }

    @Test
    void downstreamOfACycleIsLayeredAfterItNotWithIt() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module(".", ".", "x", "jar", null, List.of(), on("y"))));
        read.put(2L, List.of(module(".", ".", "y", "jar", null, List.of(), on("x"))));
        read.put(3L, List.of(module(".", ".", "z", "jar", null, List.of(), on("x"))));
        read.put(4L, List.of(module(".", ".", "app", "jar", null, List.of(), on("z"))));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.layers()).containsExactly(List.of(1L, 2L), List.of(3L));
        assertThat(plan.cyclic()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void repositoriesInACycleAreInstalledLastAndMarked() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module(".", ".", "x", "jar", null, List.of(), on("y"))));
        read.put(2L, List.of(module(".", ".", "y", "jar", null, List.of(), on("x"))));
        read.put(3L, List.of(module(".", ".", "base", "pom", null, List.of())));
        read.put(4L, List.of(module(".", ".", "app", "jar", acme("base"), List.of(), on("x"))));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.layers()).containsExactly(List.of(3L), List.of(1L, 2L));
        assertThat(plan.cyclic()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void unresolvedVersionsNeverMatch() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(new MavenModule(".", "com.acme", "lib", null, List.of(), List.of(), true, "jar", null,
                List.of(), ".")));
        read.put(2L, List.of(module(".", ".", "app", "jar", null, List.of(),
                new DependencyRecord("com.acme", "lib", null, "compile"))));

        assertThat(ArtifactProviders.plan(read).provisions()).isEmpty();
    }
}
