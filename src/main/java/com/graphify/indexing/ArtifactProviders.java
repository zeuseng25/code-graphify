package com.graphify.indexing;

import com.graphify.maven.Gav;
import com.graphify.maven.MavenModule;
import com.graphify.store.DependencyRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which scanned repositories must install artifacts for which others (spec §3.2): a module's parent, imported BOMs
 * and dependencies are matched to the modules of other project roots by exact groupId:artifactId:version. Only
 * references inside one project root (one reactor) are excluded. Providers are ordered in layers (Kahn's algorithm
 * over the strongly connected components, ties in the caller's order); a cycle of repositories is a layer of its own.
 */
public final class ArtifactProviders {

    /**
     * What one provider installs: the consumed coordinates with their packaging, and the project roots to build,
     * ordered so a root comes after the roots it needs.
     */
    public record Provision(long repositoryId, Map<Gav, String> consumed, List<String> roots) {
    }

    /**
     * {@code providersOf} lists, per consumer, the other repositories it needs; {@code transitiveProvidersOf} is its
     * closure (a consumer re-indexes when any provider in it was installed); {@code cyclic} holds the members of
     * multi-repository cycles.
     */
    public record Plan(Map<Long, Provision> provisions, Map<Long, Set<Long>> providersOf,
            Map<Long, Set<Long>> transitiveProvidersOf, List<List<Long>> layers, Set<Long> cyclic) {
    }

    private record Declaration(long repository, MavenModule module) {
        String root() {
            return module.projectRoot() == null ? "." : module.projectRoot();
        }
    }

    private ArtifactProviders() {
    }

    /** Everything a module may need from elsewhere; partial or unresolved coordinates are left out. */
    public static Set<Gav> references(MavenModule module) {
        Set<Gav> references = new LinkedHashSet<>();
        if (module.parent() != null) {
            references.add(module.parent());
        }
        references.addAll(module.imports());
        for (DependencyRecord dependency : module.dependencies()) {
            Gav gav = Gav.of(dependency.groupId(), dependency.artifactId(), dependency.version());
            if (gav != null) {
                references.add(gav);
            }
        }
        return references;
    }

    /** References that no module of any repository declares (third-party libraries, or repositories not read yet). */
    public static Set<Gav> unmatched(Map<Long, List<MavenModule>> modulesByRepository) {
        Map<Gav, List<Declaration>> declared = declarations(modulesByRepository);
        Set<Gav> unmatched = new LinkedHashSet<>();
        modulesByRepository.values().forEach(modules -> modules.forEach(module -> references(module).stream()
                .filter(reference -> !declared.containsKey(reference)).forEach(unmatched::add)));
        return unmatched;
    }

    public static Plan plan(Map<Long, List<MavenModule>> modulesByRepository) {
        Map<Gav, List<Declaration>> declared = declarations(modulesByRepository);
        Map<Long, Set<Long>> providersOf = new LinkedHashMap<>();
        Map<Long, Map<Gav, String>> consumed = new LinkedHashMap<>();
        Map<Long, Set<String>> seeds = new LinkedHashMap<>();
        Map<Long, Map<String, Set<String>>> rootNeeds = new LinkedHashMap<>();
        modulesByRepository.forEach((consumer, modules) -> {
            for (MavenModule module : modules) {
                String consumerRoot = module.projectRoot() == null ? "." : module.projectRoot();
                for (Gav reference : references(module)) {
                    List<Declaration> declarers = declared.get(reference);
                    if (declarers == null) {
                        continue;
                    }
                    Declaration own = declarers.stream().filter(d -> d.repository() == consumer).findFirst()
                            .orElse(null);
                    Declaration provider = own != null ? own : declarers.get(0);
                    if (own != null) {
                        if (own.root().equals(consumerRoot)) {
                            continue;
                        }
                        rootNeeds.computeIfAbsent(consumer, c -> new LinkedHashMap<>())
                                .computeIfAbsent(consumerRoot, r -> new LinkedHashSet<>()).add(own.root());
                    } else {
                        providersOf.computeIfAbsent(consumer, c -> new LinkedHashSet<>()).add(provider.repository());
                    }
                    consumed.computeIfAbsent(provider.repository(), p -> new LinkedHashMap<>())
                            .put(reference, provider.module().packaging());
                    seeds.computeIfAbsent(provider.repository(), p -> new LinkedHashSet<>()).add(provider.root());
                }
            }
        });
        Map<Long, Provision> provisions = new LinkedHashMap<>();
        for (Long repository : modulesByRepository.keySet()) {
            if (consumed.containsKey(repository)) {
                List<String> roots = new ArrayList<>();
                Map<String, Set<String>> needs = rootNeeds.getOrDefault(repository, Map.of());
                Set<String> seen = new LinkedHashSet<>();
                for (String seed : seeds.get(repository)) {
                    visitRoot(seed, needs, seen, roots);
                }
                provisions.put(repository, new Provision(repository,
                        Collections.unmodifiableMap(consumed.get(repository)), Collections.unmodifiableList(roots)));
            }
        }
        Map<Long, Set<Long>> graph = new LinkedHashMap<>();
        provisions.keySet().forEach(p -> graph.put(p, providersOf.getOrDefault(p, Set.of())));
        Set<Long> cyclic = new LinkedHashSet<>();
        List<List<Long>> layers = layers(graph, cyclic);
        Map<Long, Set<Long>> transitive = new LinkedHashMap<>();
        providersOf.keySet().forEach(consumer -> {
            Set<Long> closure = new LinkedHashSet<>();
            Deque<Long> pending = new ArrayDeque<>(providersOf.get(consumer));
            while (!pending.isEmpty()) {
                Long next = pending.poll();
                if (!next.equals(consumer) && closure.add(next)) {
                    pending.addAll(providersOf.getOrDefault(next, Set.of()));
                }
            }
            transitive.put(consumer, Collections.unmodifiableSet(closure));
        });
        providersOf.replaceAll((k, v) -> Collections.unmodifiableSet(v));
        return new Plan(Collections.unmodifiableMap(provisions), Collections.unmodifiableMap(providersOf),
                Collections.unmodifiableMap(transitive), layers, Collections.unmodifiableSet(cyclic));
    }

    /** Depth-first, needed roots first; a cross-root cycle inside one repository keeps discovery order. */
    private static void visitRoot(String root, Map<String, Set<String>> needs, Set<String> seen, List<String> out) {
        if (!seen.add(root)) {
            return;
        }
        for (String need : needs.getOrDefault(root, Set.of())) {
            visitRoot(need, needs, seen, out);
        }
        out.add(root);
    }

    /** Kahn over the strongly connected components; a multi-member component is a layer of its own. */
    private static List<List<Long>> layers(Map<Long, Set<Long>> graph, Set<Long> cyclic) {
        List<Long> order = new ArrayList<>(graph.keySet());
        Map<Long, Integer> index = new HashMap<>();
        Map<Long, Integer> low = new HashMap<>();
        Set<Long> onStack = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        List<List<Long>> components = new ArrayList<>();
        int[] counter = {0};
        for (Long node : order) {
            if (!index.containsKey(node)) {
                strongConnect(node, graph, index, low, onStack, stack, components, counter);
            }
        }
        Map<Long, Integer> componentOf = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            List<Long> members = components.get(i);
            members.sort((a, b) -> Integer.compare(order.indexOf(a), order.indexOf(b)));
            for (Long member : members) {
                componentOf.put(member, i);
            }
        }
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            remaining.add(i);
        }
        remaining.sort((a, b) -> Integer.compare(order.indexOf(components.get(a).get(0)),
                order.indexOf(components.get(b).get(0))));
        Set<Integer> placed = new LinkedHashSet<>();
        List<List<Long>> layers = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<Integer> ready = new ArrayList<>();
            for (Integer component : remaining) {
                boolean free = true;
                for (Long member : components.get(component)) {
                    for (Long need : graph.get(member)) {
                        Integer needed = componentOf.get(need);
                        if (needed != null && !needed.equals(component) && !placed.contains(needed)) {
                            free = false;
                        }
                    }
                }
                if (free) {
                    ready.add(component);
                }
            }
            List<Long> singles = new ArrayList<>();
            List<List<Long>> cycles = new ArrayList<>();
            for (Integer component : ready) {
                List<Long> members = components.get(component);
                if (members.size() == 1) {
                    singles.add(members.get(0));
                } else {
                    cycles.add(members);
                    cyclic.addAll(members);
                }
            }
            if (!singles.isEmpty()) {
                layers.add(singles);
            }
            layers.addAll(cycles);
            placed.addAll(ready);
            remaining.removeAll(ready);
        }
        return Collections.unmodifiableList(layers);
    }

    private static void strongConnect(Long node, Map<Long, Set<Long>> graph, Map<Long, Integer> index,
            Map<Long, Integer> low, Set<Long> onStack, Deque<Long> stack, List<List<Long>> components,
            int[] counter) {
        index.put(node, counter[0]);
        low.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);
        for (Long next : graph.get(node)) {
            if (!graph.containsKey(next)) {
                continue;
            }
            if (!index.containsKey(next)) {
                strongConnect(next, graph, index, low, onStack, stack, components, counter);
                low.put(node, Math.min(low.get(node), low.get(next)));
            } else if (onStack.contains(next)) {
                low.put(node, Math.min(low.get(node), index.get(next)));
            }
        }
        if (low.get(node).equals(index.get(node))) {
            List<Long> component = new ArrayList<>();
            Long member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            components.add(component);
        }
    }

    /** Coordinate → declaring modules in the caller's order; the first declarer is the provider. */
    private static Map<Gav, List<Declaration>> declarations(Map<Long, List<MavenModule>> modulesByRepository) {
        Map<Gav, List<Declaration>> declared = new HashMap<>();
        modulesByRepository.forEach((repository, modules) -> modules.forEach(module -> {
            if (module.gav() != null) {
                declared.computeIfAbsent(module.gav(), g -> new ArrayList<>())
                        .add(new Declaration(repository, module));
            }
        }));
        return declared;
    }
}
