package com.graphify.impact;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import com.graphify.indexer.model.UsageKind;
import com.graphify.repository.RepositoryRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Breadth-first impact analysis (spec §5). Level 1 lists usages of the seeds whose kind is shown at level 1; deeper
 * levels follow only propagating kinds. Overridden methods are followed as dispatch targets, never their OVERRIDES
 * edges, so sibling implementations are not reported.
 *
 * <p>Dispatch targets are SOURCE methods only. A BINARY overridden method ({@code Object#toString},
 * {@code Runnable#run}, {@code Comparable#compareTo}, ...) is never followed, because its callers are every caller of
 * that JDK or library method in every repository. The trade-off: callers that reach the changed code only through an
 * interface from a non-indexed jar are not followed. Corporate libraries are expected to be indexed from source, so
 * their interfaces are SOURCE symbols and still dispatch.
 *
 * <p>Node confidence: requested seeds (and their name-only twins) are EXACT. A newly reached node takes the weaker of
 * the edge's confidence and the confidence of the node the edge points to (EXACT &gt; RECOVERED &gt; NAME_ONLY), and a
 * dispatch target takes the confidence of the node that overrides it. When a known node is reached again, it keeps the
 * stronger value. This is an approximation: an improvement found later is not propagated back to nodes already reached
 * through it, so a node can be reported weaker than its best path.
 */
public final class ImpactEngine {

    private static final Set<SymbolKind> TYPE_KINDS = EnumSet.of(SymbolKind.CLASS, SymbolKind.INTERFACE,
            SymbolKind.ENUM, SymbolKind.RECORD, SymbolKind.ANNOTATION_TYPE);
    private static final Set<SymbolKind> CALLABLE_KINDS = EnumSet.of(SymbolKind.METHOD, SymbolKind.CONSTRUCTOR);

    private static final UsageDetail NO_DETAIL = new UsageDetail(null, 0, 0, null);

    private final ImpactGraph graph;

    public ImpactEngine(ImpactGraph graph) {
        this.graph = graph;
    }

    public ImpactResult analyze(ImpactRequest request, ImpactLimits limits, Map<UsageKind, ImpactRule> rules,
            Map<String, String> entryPointLabels) {
        Plan plan = Plan.of(request, limits);
        Map<Long, ImpactSymbol> targets = graph.symbols(plan.symbolIds());
        for (Long id : plan.symbolIds()) {
            if (!targets.containsKey(id)) {
                throw new NotFoundException("No symbol with id " + id);
            }
        }
        Set<UsageKind> levelOneKinds = kinds(rules, ImpactRule::shownAtLevel1);
        Set<UsageKind> propagating = kinds(rules, ImpactRule::propagates);

        Set<Long> seedTwins = new HashSet<>();
        Set<Long> seeds = seeds(plan.symbolIds(), targets, plan.signature(), seedTwins);
        Search search = new Search(seeds, propagating, limits.maxResults());
        search.twins.addAll(seedTwins);
        Set<Long> frontier = seeds;
        Set<Long> dispatchFrontier = plan.dispatch() ? dispatchTargets(search, seeds, 0) : Set.of();
        for (int level = 1; level <= plan.depth(); level++) {
            if (frontier.isEmpty() && dispatchFrontier.isEmpty()) {
                break;
            }
            Set<UsageKind> kinds = level == 1 ? levelOneKinds : propagating;
            Set<UsageKind> dispatchKinds = EnumSet.noneOf(UsageKind.class);
            dispatchKinds.addAll(kinds);
            dispatchKinds.remove(UsageKind.OVERRIDES);
            Set<Long> next = new LinkedHashSet<>();
            search.collect(usagesTo(frontier, kinds, plan.confidences()), false, level, next);
            search.collect(usagesTo(dispatchFrontier, dispatchKinds, plan.confidences()), true, level, next);
            if (search.truncated || level == plan.depth()) {
                break;
            }
            frontier = withTwins(search, next, level);
            dispatchFrontier = plan.dispatch() && !next.isEmpty() ? dispatchTargets(search, next, level) : Set.of();
        }
        return assemble(search, entryPointLabels);
    }

    /**
     * The methods {@code overriders} override that are not yet known, minus every BINARY one: overriding
     * {@code Object#toString} or {@code Runnable#run} would otherwise pull in every caller of the JDK method across all
     * repositories. Trade-off: a caller that reaches the code only through an interface from a non-indexed jar is not
     * followed; corporate libraries are expected to be indexed from source.
     */
    private Set<Long> dispatchTargets(Search search, Set<Long> overriders, int level) {
        Map<Long, Confidence> candidates = new LinkedHashMap<>();
        graph.overriddenMethods(overriders).forEach((overrider, list) -> list.forEach(id -> {
            if (!search.levels.containsKey(id) && !search.dispatchLevels.containsKey(id)) {
                candidates.merge(id, search.confidenceOf(overrider), ImpactEngine::stronger);
            }
        }));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        Map<Long, ImpactSymbol> symbols = graph.symbols(candidates.keySet());
        List<ImpactSymbol> accepted = new ArrayList<>();
        candidates.keySet().forEach(id -> {
            ImpactSymbol symbol = symbols.get(id);
            if (symbol != null && symbol.origin() != SymbolOrigin.BINARY) {
                accepted.add(symbol);
            }
        });
        Map<Long, Long> twins = twinsOf(accepted);
        Set<Long> targets = new LinkedHashSet<>();
        for (ImpactSymbol symbol : accepted) {
            Confidence confidence = candidates.get(symbol.id());
            addDispatchTarget(search, symbol.id(), level, confidence, targets);
            Long twin = twins.get(symbol.id());
            if (twin != null) {
                // the twin's own origin is irrelevant: it stands for calls to this SOURCE method
                if (addDispatchTarget(search, twin, level, confidence, targets)) {
                    search.twins.add(twin);
                }
            }
        }
        return targets;
    }

    private static boolean addDispatchTarget(Search search, long id, int level, Confidence confidence,
            Set<Long> targets) {
        if (!search.levels.containsKey(id) && !search.dispatchLevels.containsKey(id)) {
            search.dispatchLevels.put(id, level);
            search.confidences.put(id, confidence);
            targets.add(id);
            return true;
        }
        return false;
    }

    /**
     * {@code next} plus the name-only twin of every method or constructor in it. A twin is not an affected node by
     * itself: it only stands for the calls the indexer could not bind, so its usages are followed at the next level
     * with the confidence of the node it belongs to. It is reported as a TWIN node at {@code level}.
     */
    private Set<Long> withTwins(Search search, Set<Long> next, int level) {
        Map<Long, Long> twins = twinsOf(graph.symbols(next).values());
        if (twins.isEmpty()) {
            return next;
        }
        Set<Long> frontier = new LinkedHashSet<>();
        for (Long id : next) {
            frontier.add(id);
            Long twin = twins.get(id);
            if (twin != null && search.expanded.add(twin)) {
                search.confidences.putIfAbsent(twin, search.confidenceOf(id));
                if (!search.levels.containsKey(twin)) {
                    search.twins.add(twin);
                    search.twinLevels.putIfAbsent(twin, level);
                }
                frontier.add(twin);
            }
        }
        return frontier;
    }

    /** EXACT &gt; RECOVERED &gt; NAME_ONLY: the enum's declaration order. */
    static Confidence weaker(Confidence a, Confidence b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    static Confidence stronger(Confidence a, Confidence b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private List<ImpactUsage> usagesTo(Set<Long> targets, Set<UsageKind> kinds, Set<Confidence> confidences) {
        return targets.isEmpty() || kinds.isEmpty() ? List.of() : graph.usagesTo(targets, kinds, confidences);
    }

    /**
     * Seeds in request order: each target, a type's descendants, the overriders of a method target whose change
     * reaches them, and the name-only twin of every callable. A method's overriders are seeds when the signature
     * changes (they stop compiling) or when the method is declared in an interface (its implementations are what
     * changes). A behaviour change of a concrete class method does not change its overrides, so they are not seeded.
     */
    private Set<Long> seeds(List<Long> requested, Map<Long, ImpactSymbol> targets, boolean signature,
            Set<Long> twinIds) {
        Set<Long> ordered = new LinkedHashSet<>();
        Map<Long, ImpactSymbol> known = new LinkedHashMap<>();
        Map<Long, List<Long>> overriders = overridersToSeed(requested, targets, signature);
        List<Long> overriderIds = new ArrayList<>();
        overriders.values().forEach(overriderIds::addAll);
        known.putAll(graph.symbols(overriderIds));
        for (Long id : requested) {
            ImpactSymbol target = targets.get(id);
            ordered.add(target.id());
            known.put(target.id(), target);
            if (TYPE_KINDS.contains(target.kind())) {
                List<Long> descendants = graph.descendants(target.id());
                ordered.addAll(descendants);
                known.putAll(graph.symbols(descendants));
            }
            ordered.addAll(overriders.getOrDefault(target.id(), List.of()));
        }
        Map<Long, Long> twins = twinsOf(known.values());
        Set<Long> seeds = new LinkedHashSet<>();
        for (Long id : ordered) {
            seeds.add(id);
            Long twin = twins.get(id);
            if (twin != null) {
                seeds.add(twin);
                if (!ordered.contains(twin)) {
                    twinIds.add(twin);
                }
            }
        }
        return seeds;
    }

    private Map<Long, List<Long>> overridersToSeed(List<Long> requested, Map<Long, ImpactSymbol> targets,
            boolean signature) {
        List<ImpactSymbol> methods = requested.stream().map(targets::get)
                .filter(t -> t.kind() == SymbolKind.METHOD).toList();
        if (methods.isEmpty()) {
            return Map.of();
        }
        Set<Long> parentIds = new LinkedHashSet<>();
        methods.forEach(m -> {
            if (m.parentId() != null) {
                parentIds.add(m.parentId());
            }
        });
        Map<Long, ImpactSymbol> parents = signature || parentIds.isEmpty() ? Map.of() : graph.symbols(parentIds);
        List<Long> reached = methods.stream().filter(m -> signature || (m.parentId() != null
                && parents.containsKey(m.parentId()) && parents.get(m.parentId()).kind() == SymbolKind.INTERFACE))
                .map(ImpactSymbol::id).toList();
        return reached.isEmpty() ? Map.of() : graph.overriders(reached);
    }

    /** symbol id → id of its name-only twin, for the callables that have one; one batched key lookup. */
    private Map<Long, Long> twinsOf(Collection<ImpactSymbol> symbols) {
        Map<Long, String> keys = new LinkedHashMap<>();
        for (ImpactSymbol symbol : symbols) {
            if (CALLABLE_KINDS.contains(symbol.kind())) {
                nameOnlyKey(symbol.key()).ifPresent(key -> keys.put(symbol.id(), key));
            }
        }
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> ids = graph.idsOfKeys(keys.values());
        Map<Long, Long> twins = new LinkedHashMap<>();
        keys.forEach((id, key) -> {
            Long twin = ids.get(key);
            if (twin != null && !twin.equals(id)) {
                twins.put(id, twin);
            }
        });
        return twins;
    }

    /** {@code C#m(a,b)} → {@code C#m/2}: the key the indexer gives a call it could not bind (spec §4.2). */
    static Optional<String> nameOnlyKey(String key) {
        int hash = key.indexOf('#');
        int open = hash < 0 ? -1 : key.indexOf('(', hash);
        if (open < 0 || !key.endsWith(")")) {
            return Optional.empty();
        }
        String parameters = key.substring(open + 1, key.length() - 1);
        int count = parameters.isEmpty() ? 0 : parameters.split(",", -1).length;
        return Optional.of(key.substring(0, open) + "/" + count);
    }

    private ImpactResult assemble(Search search, Map<String, String> entryPointLabels) {
        Set<Long> ids = new LinkedHashSet<>(search.levels.keySet());
        ids.addAll(search.dispatchLevels.keySet());
        ids.addAll(search.twinLevels.keySet());
        Map<Long, ImpactSymbol> info = graph.symbols(ids);
        Set<Long> moduleIds = new LinkedHashSet<>();
        search.found.forEach(f -> moduleIds.add(f.usage().moduleId()));
        Map<Long, RepoState> states = graph.repoStates(moduleIds);

        List<ImpactNode> nodes = new ArrayList<>();
        search.levels.forEach((id, level) -> node(info.get(id), level,
                search.twins.contains(id) ? NodeRole.TWIN
                        : search.seeds.contains(id) ? NodeRole.SEED : NodeRole.AFFECTED,
                search.confidenceOf(id)).ifPresent(nodes::add));
        search.dispatchLevels.forEach((id, level) -> {
            if (!search.levels.containsKey(id)) {
                node(info.get(id), level, search.twins.contains(id) ? NodeRole.TWIN : NodeRole.DISPATCH,
                        search.confidenceOf(id)).ifPresent(nodes::add);
            }
        });
        search.twinLevels.forEach((id, level) -> {
            if (!search.levels.containsKey(id) && !search.dispatchLevels.containsKey(id)) {
                node(info.get(id), level, NodeRole.TWIN, search.confidenceOf(id)).ifPresent(nodes::add);
            }
        });
        List<Long> usageIds = search.found.stream().map(f -> f.usage().id()).toList();
        Map<Long, UsageDetail> details = usageIds.isEmpty() ? Map.of() : graph.usageDetails(usageIds);
        List<ImpactEdge> edges = new ArrayList<>();
        for (Found found : search.found) {
            ImpactUsage u = found.usage();
            RepoState state = states.get(u.moduleId());
            UsageDetail detail = details.getOrDefault(u.id(), NO_DETAIL);
            edges.add(new ImpactEdge(u.fromId(), u.toId(), u.kind(), u.confidence(), found.level(),
                    found.viaDispatch(), u.moduleId(), state == null ? null : state.repositoryRef(),
                    state == null ? null : state.modulePath(), detail.filePath(), detail.line(), detail.column(),
                    detail.snippet()));
        }
        List<EntryPoint> entryPoints = new EntryPointFinder(graph).find(nodes, info, entryPointLabels);
        List<RepoState> staleness = states.values().stream()
                .sorted(Comparator.comparing(RepoState::repositoryRef, RepoState.REPOSITORY_ORDER)
                        .thenComparing(RepoState::modulePath))
                .toList();
        return new ImpactResult(summary(nodes, edges, info, states), nodes, edges, entryPoints, staleness, List.of(),
                search.truncated);
    }

    private static Optional<ImpactNode> node(ImpactSymbol symbol, int level, NodeRole role, Confidence confidence) {
        return symbol == null ? Optional.empty()
                : Optional.of(new ImpactNode(symbol.id(), symbol.key(), symbol.kind(), symbol.display(), level, role,
                        confidence, role == NodeRole.TWIN || symbol.nameOnly()));
    }

    private static ImpactSummary summary(List<ImpactNode> nodes, List<ImpactEdge> edges, Map<Long, ImpactSymbol> info,
            Map<Long, RepoState> states) {
        Set<String> classes = new HashSet<>();
        int methods = 0;
        Map<Confidence, Integer> nodesByConfidence = zeroPerConfidence();
        for (ImpactNode node : nodes) {
            if (node.role() != NodeRole.AFFECTED) {
                continue;
            }
            nodesByConfidence.merge(node.confidence(), 1, Integer::sum);
            classes.add(info.get(node.symbolId()).classFqn());
            if (CALLABLE_KINDS.contains(node.kind())) {
                methods++;
            }
        }
        Map<Integer, Integer> byLevel = new TreeMap<>();
        Map<Confidence, Integer> byConfidence = zeroPerConfidence();
        Set<Long> modules = new HashSet<>();
        Set<Long> repositories = new HashSet<>();
        Set<RepositoryRef> partial = new TreeSet<>(RepoState.REPOSITORY_ORDER);
        for (ImpactEdge edge : edges) {
            byLevel.merge(edge.level(), 1, Integer::sum);
            byConfidence.merge(edge.confidence(), 1, Integer::sum);
            modules.add(edge.moduleId());
            RepoState state = states.get(edge.moduleId());
            if (state != null) {
                repositories.add(state.repositoryId());
                if (!"FULL".equals(state.classpathMode())) {
                    partial.add(state.repositoryRef());
                }
            }
        }
        return new ImpactSummary(repositories.size(), modules.size(), classes.size(), methods, edges.size(), byLevel,
                byConfidence, nodesByConfidence, List.copyOf(partial));
    }

    private static Map<Confidence, Integer> zeroPerConfidence() {
        Map<Confidence, Integer> counts = new EnumMap<>(Confidence.class);
        for (Confidence confidence : Confidence.values()) {
            counts.put(confidence, 0);
        }
        return counts;
    }

    private static Set<UsageKind> kinds(Map<UsageKind, ImpactRule> rules, Predicate<ImpactRule> test) {
        Set<UsageKind> kinds = EnumSet.noneOf(UsageKind.class);
        rules.values().stream().filter(test).forEach(rule -> kinds.add(rule.kind()));
        return kinds;
    }

    private record Found(ImpactUsage usage, int level, boolean viaDispatch) {
    }

    /** Mutable state of one BFS run. */
    private static final class Search {

        final Set<Long> seeds;
        final Set<UsageKind> propagating;
        final int maxResults;
        final Map<Long, Integer> levels = new LinkedHashMap<>();
        final Map<Long, Integer> dispatchLevels = new LinkedHashMap<>();
        final Map<Long, Confidence> confidences = new HashMap<>();
        final Set<Long> expanded = new HashSet<>();
        /** Name-only twins the engine added; reported as TWIN nodes. {@code twinLevels} holds those not otherwise reached. */
        final Set<Long> twins = new HashSet<>();
        final Map<Long, Integer> twinLevels = new LinkedHashMap<>();
        final Set<Long> seenUsages = new HashSet<>();
        final List<Found> found = new ArrayList<>();
        boolean truncated;

        Confidence confidenceOf(long id) {
            return confidences.getOrDefault(id, Confidence.EXACT);
        }

        Search(Set<Long> seeds, Set<UsageKind> propagating, int maxResults) {
            this.seeds = seeds;
            this.propagating = propagating;
            this.maxResults = maxResults;
            seeds.forEach(id -> {
                levels.put(id, 0);
                confidences.put(id, Confidence.EXACT);
            });
            expanded.addAll(seeds);
        }

        void collect(List<ImpactUsage> usages, boolean viaDispatch, int level, Set<Long> next) {
            for (ImpactUsage usage : usages) {
                if (!seenUsages.add(usage.id())) {
                    continue;
                }
                boolean known = levels.containsKey(usage.fromId());
                if (!known && levels.size() - seeds.size() >= maxResults) {
                    truncated = true;
                    continue;
                }
                found.add(new Found(usage, level, viaDispatch));
                Confidence reached = weaker(usage.confidence(), confidenceOf(usage.toId()));
                if (!known) {
                    levels.put(usage.fromId(), level);
                    confidences.put(usage.fromId(), reached);
                } else {
                    confidences.merge(usage.fromId(), reached, ImpactEngine::stronger);
                }
                if (propagating.contains(usage.kind()) && expanded.add(usage.fromId())) {
                    next.add(usage.fromId());
                }
            }
        }
    }

    /** The validated request: defaults applied, SIGNATURE forcing depth 1 and no dispatch (spec §5.3). */
    private record Plan(List<Long> symbolIds, int depth, Set<Confidence> confidences, boolean dispatch,
            boolean signature) {

        static Plan of(ImpactRequest request, ImpactLimits limits) {
            if (request == null || request.symbolIds() == null || request.symbolIds().isEmpty()) {
                throw new InvalidRequestException("symbolIds must not be empty");
            }
            if (request.symbolIds().stream().anyMatch(Objects::isNull)) {
                throw new InvalidRequestException("symbolIds must not contain null");
            }
            int depth = request.depth() == null ? limits.defaultDepth() : request.depth();
            if (depth < 1 || depth > limits.maxDepth()) {
                throw new InvalidRequestException("depth must be between 1 and " + limits.maxDepth());
            }
            if (request.confidences() != null && request.confidences().stream().anyMatch(Objects::isNull)) {
                throw new InvalidRequestException("confidences must not contain null");
            }
            Set<Confidence> confidences = request.confidences() == null || request.confidences().isEmpty()
                    ? EnumSet.allOf(Confidence.class) : EnumSet.copyOf(request.confidences());
            boolean dispatch = request.includeDispatch() == null || request.includeDispatch();
            boolean signature = request.changeType() == ChangeType.SIGNATURE;
            if (signature) {
                depth = 1;
                dispatch = false;
            }
            return new Plan(List.copyOf(new LinkedHashSet<>(request.symbolIds())), depth, confidences, dispatch,
                    signature);
        }
    }
}
