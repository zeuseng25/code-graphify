package com.graphify.impact;

import com.graphify.indexer.model.SymbolKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which reached methods are entry points, by the ENTRY_POINT_ANNOTATION list (spec §5.6). HTTP paths are reported
 * only when fully resolvable from literals, otherwise null.
 *
 * <p>A seed or affected method with no entry annotation of its own inherits those of the methods it overrides, as
 * Spring does for an interface such as {@code OrdersApi}: the class-level prefix then comes from the overridden
 * method's type, and the entry point is reported on the implementing method, with its key, module and repository.
 * Known limitation: a class-level mapping inherited by a method that is itself annotated (a controller extending an
 * annotated base class) is not applied.
 */
final class EntryPointFinder {

    private static final Set<SymbolKind> CALLABLE_KINDS = EnumSet.of(SymbolKind.METHOD, SymbolKind.CONSTRUCTOR);

    /** Spring fact: a class-level prefix always comes from this annotation, whether or not it is an enabled label. */
    private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";

    private final ImpactGraph graph;

    EntryPointFinder(ImpactGraph graph) {
        this.graph = graph;
    }

    /** An entry annotation that applies to {@code symbol}; it is written on {@code declared} (itself or a supertype's). */
    private record Applied(ImpactSymbol symbol, ImpactSymbol declared, AnnotationUse use, long moduleId) {
    }

    List<EntryPoint> find(Collection<ImpactNode> nodes, Map<Long, ImpactSymbol> info, Map<String, String> labels) {
        if (labels.isEmpty()) {
            return List.of();
        }
        Set<Long> callables = new LinkedHashSet<>();
        for (ImpactNode node : nodes) {
            if ((node.role() == NodeRole.SEED || node.role() == NodeRole.AFFECTED) && CALLABLE_KINDS.contains(node.kind())) {
                callables.add(node.symbolId());
            }
        }
        if (callables.isEmpty()) {
            return List.of();
        }
        List<Applied> applied = new ArrayList<>();
        Set<Long> annotated = new HashSet<>();
        for (AnnotationUse use : graph.annotationsOn(callables, labels.keySet())) {
            ImpactSymbol symbol = info.get(use.symbolId());
            applied.add(new Applied(symbol, symbol, use, use.moduleId()));
            annotated.add(use.symbolId());
        }
        Set<Long> unannotated = new LinkedHashSet<>(callables);
        unannotated.removeAll(annotated);
        if (!unannotated.isEmpty()) {
            applied.addAll(inherited(unannotated, info, labels.keySet()));
        }
        if (applied.isEmpty()) {
            return List.of();
        }

        Set<Long> owners = new LinkedHashSet<>();
        applied.forEach(a -> {
            if (a.declared().parentId() != null) {
                owners.add(a.declared().parentId());
            }
        });
        Set<String> mappingKeys = new LinkedHashSet<>();
        labels.keySet().stream().filter(MappingPaths::isMapping).forEach(mappingKeys::add);
        mappingKeys.add(REQUEST_MAPPING);
        // absent key = no class mapping, empty Optional = unresolved prefix, present = resolved prefix
        Map<Long, Optional<String>> classPrefixes = new HashMap<>();
        if (!owners.isEmpty()) {
            for (AnnotationUse classUse : graph.annotationsOn(owners, mappingKeys)) {
                classPrefixes.putIfAbsent(classUse.symbolId(), MappingPaths.path(classUse.snippet()));
            }
        }
        Set<Long> moduleIds = new LinkedHashSet<>();
        applied.forEach(a -> moduleIds.add(a.moduleId()));
        Map<Long, RepoState> states = graph.repoStates(moduleIds);

        List<EntryPoint> entryPoints = new ArrayList<>();
        for (Applied a : applied) {
            AnnotationUse use = a.use();
            ImpactSymbol symbol = a.symbol();
            RepoState state = states.get(a.moduleId());
            String httpMethod = null;
            String httpPath = null;
            boolean http = MappingPaths.isMapping(use.annotationKey());
            if (http) {
                httpMethod = MappingPaths.httpMethod(use.annotationKey(), use.snippet());
                Optional<String> own = MappingPaths.path(use.snippet());
                Long owner = a.declared().parentId();
                Optional<String> prefix = owner == null ? Optional.of("")
                        : classPrefixes.getOrDefault(owner, Optional.of(""));
                if (own.isPresent() && prefix.isPresent()) {
                    httpPath = MappingPaths.join(prefix.get(), own.get());
                }
            }
            entryPoints.add(new EntryPoint(symbol.id(), symbol.key(), symbol.display(),
                    state == null ? null : state.repositoryRef(), state == null ? null : state.modulePath(),
                    labels.get(use.annotationKey()), use.snippet(), http, httpMethod, httpPath));
        }
        entryPoints.sort(Comparator.comparing(EntryPoint::repository,
                        Comparator.nullsFirst(RepoState.REPOSITORY_ORDER))
                .thenComparing(EntryPoint::key)
                .thenComparing(e -> e.label() == null ? "" : e.label())
                .thenComparing(e -> e.annotation() == null ? "" : e.annotation())
                .thenComparing(e -> e.modulePath() == null ? "" : e.modulePath()));
        return entryPoints;
    }

    /** Entry annotations of the methods the given ones override, once per module the implementation is declared in. */
    private List<Applied> inherited(Set<Long> implementations, Map<Long, ImpactSymbol> info, Set<String> labelKeys) {
        Map<Long, List<Long>> overridden = graph.overriddenMethods(implementations);
        Set<Long> supers = new LinkedHashSet<>();
        overridden.values().forEach(supers::addAll);
        if (supers.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AnnotationUse>> usesBySuper = new HashMap<>();
        for (AnnotationUse use : graph.annotationsOn(supers, labelKeys)) {
            usesBySuper.computeIfAbsent(use.symbolId(), k -> new ArrayList<>()).add(use);
        }
        if (usesBySuper.isEmpty()) {
            return List.of();
        }
        Map<Long, ImpactSymbol> superSymbols = graph.symbols(usesBySuper.keySet());
        Map<Long, List<Long>> modules = graph.declaringModules(implementations);
        List<Applied> applied = new ArrayList<>();
        for (Long id : implementations) {
            Set<String> seen = new HashSet<>();
            for (Long superId : overridden.getOrDefault(id, List.of())) {
                ImpactSymbol declared = superSymbols.get(superId);
                for (AnnotationUse use : usesBySuper.getOrDefault(superId, List.of())) {
                    if (declared == null || !seen.add(use.annotationKey() + '\n' + use.snippet())) {
                        continue;
                    }
                    for (Long module : modules.getOrDefault(id, List.of())) {
                        applied.add(new Applied(info.get(id), declared, use, module));
                    }
                }
            }
        }
        return applied;
    }
}
