package com.graphify.repograph;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.TreeMap;

/** Builds one level of a repository graph from its class graph, rolling up while over the node limit (spec §9.1). */
final class GraphBuilder {

    /** Node id prefixes, one per node family. */
    private static final String MODULE_ID = "module:";
    private static final String PACKAGE_ID = "package:";
    private static final String CLASS_ID = "class:";
    private static final String MEMBER_ID = "member:";
    private static final String EXTERNAL_ID = "external:";

    private static final class NodeAcc {

        private final String id;
        private final String label;
        private final NodeType type;
        private final ClassMetrics metrics;
        private final Set<String> members = new HashSet<>();

        private NodeAcc(String id, String label, NodeType type, ClassMetrics metrics) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.metrics = metrics;
        }

        private GraphNode node() {
            return new GraphNode(id, label, type, members.size(), metrics);
        }
    }

    private static final class EdgeAcc {

        private long weight;
        private final EnumMap<UsageKind, Long> kinds = new EnumMap<>(UsageKind.class);

        private void add(UsageKind kind, long count) {
            weight += count;
            kinds.merge(kind, count, Long::sum);
        }
    }

    private record Pair(String from, String to) implements Comparable<Pair> {

        @Override
        public int compareTo(Pair other) {
            int byFrom = from.compareTo(other.from);
            return byFrom != 0 ? byFrom : to.compareTo(other.to);
        }
    }

    private record Assembly(List<GraphNode> nodes, List<GraphEdge> edges) {
    }

    /** Declared classes (enough for METHOD level); the full graph is only needed when a view is rolled up. */
    private final ClassGraph graph;
    private final Supplier<ClassGraph> full;
    private ClassGraph fullGraph;
    private final Map<Long, ClassMetrics> metrics;

    GraphBuilder(ClassGraph graph, Map<Long, ClassMetrics> metrics) {
        this(graph, () -> graph, metrics);
    }

    GraphBuilder(ClassGraph graph, Supplier<ClassGraph> full, Map<Long, ClassMetrics> metrics) {
        this.graph = graph;
        this.full = full;
        this.metrics = metrics;
    }

    private ClassGraph full() {
        if (fullGraph == null) {
            fullGraph = full.get();
        }
        return fullGraph;
    }

    RepoGraph build(GraphLevel requested, String focus, MemberGraph members, int maxNodes) {
        GraphLevel level = requested;
        String levelFocus = requested == GraphLevel.MODULE ? null : focus;
        Assembly first = null;
        while (true) {
            Assembly assembly = level == GraphLevel.METHOD ? members(members) : rollup(level, levelFocus);
            if (first == null) {
                first = assembly;
            }
            if (assembly.nodes().size() <= maxNodes || level == GraphLevel.MODULE) {
                boolean truncated = level != requested;
                String suggestion = truncated ? first.nodes().size() + " " + name(requested)
                        + " nodes exceed the node limit (" + maxNodes + "); shown by " + name(level)
                        + ", narrow it with focus" : null;
                String shownFocus = "".equals(levelFocus) ? RepoGraphAnalyzer.DEFAULT_PACKAGE : levelFocus;
                return new RepoGraph(graph.repositoryId(), requested, level, shownFocus, truncated, suggestion,
                        assembly.nodes(), assembly.edges());
            }
            levelFocus = switch (level) {
                case METHOD -> ClassGraph.packageOf(levelFocus);
                case CLASS -> levelFocus;
                case PACKAGE, MODULE -> null;
            };
            level = level.up();
        }
    }

    private Assembly rollup(GraphLevel level, String focus) {
        ClassGraph graph = full();
        Map<String, NodeAcc> nodes = new TreeMap<>();
        Map<String, String> nodeOfClass = new HashMap<>();
        for (ClassNode node : graph.classes().values()) {
            if (!inFocus(node, level, focus)) {
                continue;
            }
            NodeAcc acc = nodes.computeIfAbsent(nodeId(level, node), id -> internalNode(id, level, node));
            acc.members.add(node.fqn());
            nodeOfClass.put(node.fqn(), acc.id);
        }
        Map<Pair, EdgeAcc> edges = new HashMap<>();
        for (ClassEdge edge : graph.edges()) {
            String from = nodeOfClass.get(edge.fromFqn());
            String to = nodeOfClass.get(edge.toFqn());
            if (from != null && to != null && !from.equals(to)) {
                edges.computeIfAbsent(new Pair(from, to), pair -> new EdgeAcc()).add(edge.kind(), edge.weight());
            }
        }
        for (ExternalEdge edge : graph.external()) {
            String from = nodeOfClass.get(edge.fromFqn());
            if (from == null) {
                continue;
            }
            NodeAcc external = externalNode(nodes, edge.group());
            external.members.add(edge.toFqn());
            edges.computeIfAbsent(new Pair(from, external.id), pair -> new EdgeAcc()).add(edge.kind(), edge.weight());
        }
        return assemble(nodes, edges);
    }

    private Assembly members(MemberGraph members) {
        Map<String, NodeAcc> nodes = new TreeMap<>();
        Map<String, ExternalGroup> groups = new HashMap<>(graph.externalGroups());
        groups.putAll(members.externalGroups());
        members.focusMembers().forEach(ref -> memberNode(nodes, ref));
        Map<Pair, EdgeAcc> edges = new HashMap<>();
        for (MemberUse use : members.uses()) {
            String from = endpoint(nodes, use.from(), groups);
            String to = endpoint(nodes, use.to(), groups);
            if (from != null && to != null && !from.equals(to)) {
                edges.computeIfAbsent(new Pair(from, to), pair -> new EdgeAcc()).add(use.kind(), use.weight());
            }
        }
        return assemble(nodes, edges);
    }

    /** A member of a repository class is its own node; others go to their external group, or are left out. */
    private String endpoint(Map<String, NodeAcc> nodes, MemberRef ref, Map<String, ExternalGroup> groups) {
        if (graph.classes().containsKey(ref.classFqn())) {
            return memberNode(nodes, ref).id;
        }
        ExternalGroup group = groups.get(ref.classFqn());
        if (group == null) {
            return null;
        }
        NodeAcc external = externalNode(nodes, group);
        external.members.add(ref.classFqn());
        return external.id;
    }

    private static NodeAcc memberNode(Map<String, NodeAcc> nodes, MemberRef ref) {
        NodeAcc acc = nodes.computeIfAbsent(MEMBER_ID + ref.key(),
                id -> new NodeAcc(id, ref.signature(), typeOf(ref.kind()), null));
        acc.members.add(ref.key());
        return acc;
    }

    private static NodeAcc externalNode(Map<String, NodeAcc> nodes, ExternalGroup group) {
        return nodes.computeIfAbsent(EXTERNAL_ID + group.key(), id -> new NodeAcc(id, group.label(), group.type(), null));
    }

    private static boolean inFocus(ClassNode node, GraphLevel level, String focus) {
        return focus == null || level == GraphLevel.MODULE || node.packageName().equals(focus)
                || node.packageName().startsWith(focus + ".");
    }

    private static String nodeId(GraphLevel level, ClassNode node) {
        return switch (level) {
            case MODULE -> MODULE_ID + node.modulePath();
            case PACKAGE -> PACKAGE_ID + node.packageName();
            case CLASS -> CLASS_ID + node.fqn();
            case METHOD -> throw new IllegalArgumentException("METHOD nodes are members");
        };
    }

    private NodeAcc internalNode(String id, GraphLevel level, ClassNode node) {
        return switch (level) {
            case MODULE -> new NodeAcc(id, node.modulePath(), NodeType.MODULE, null);
            case PACKAGE -> new NodeAcc(id, node.packageName().isEmpty() ? RepoGraphAnalyzer.DEFAULT_PACKAGE
                    : node.packageName(), NodeType.PACKAGE, null);
            case CLASS -> new NodeAcc(id, node.fqn(), NodeType.CLASS, metrics.get(node.symbolId()));
            case METHOD -> throw new IllegalArgumentException("METHOD nodes are members");
        };
    }

    private static NodeType typeOf(SymbolKind kind) {
        return switch (kind) {
            case METHOD -> NodeType.METHOD;
            case CONSTRUCTOR -> NodeType.CONSTRUCTOR;
            case FIELD -> NodeType.FIELD;
            default -> NodeType.CLASS;
        };
    }

    private static Assembly assemble(Map<String, NodeAcc> nodes, Map<Pair, EdgeAcc> edges) {
        List<GraphNode> nodeList = nodes.values().stream().map(NodeAcc::node).toList();
        List<GraphEdge> edgeList = edges.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new GraphEdge(e.getKey().from(), e.getKey().to(), e.getValue().weight,
                        Collections.unmodifiableMap(new EnumMap<>(e.getValue().kinds))))
                .toList();
        return new Assembly(nodeList, edgeList);
    }

    private static String name(GraphLevel level) {
        return level.name().toLowerCase(Locale.ROOT);
    }
}
