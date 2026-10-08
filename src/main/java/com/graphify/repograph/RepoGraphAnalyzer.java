package com.graphify.repograph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jgrapht.Graph;
import org.jgrapht.alg.clustering.LabelPropagationClustering;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultUndirectedGraph;

/** The stored repository graph analyses (spec §9.2): degrees, dependents, communities and package cycles. */
public final class RepoGraphAnalyzer {

    /** Label of the unnamed package. */
    static final String DEFAULT_PACKAGE = "(default package)";

    /** Between a shared dominant package and the community's most used class, in a community label. */
    static final String LABEL_SEPARATOR = " · ";

    /** Metrics per class FQN, with its community; package cycles, each sorted, ordered by their first package. */
    public record Analysis(Map<String, ClassMetrics> metrics, List<List<String>> cycles) {
    }

    private record Community(int id, String label) {
    }

    private RepoGraphAnalyzer() {
    }

    public static Analysis analyze(ClassGraph graph, Set<String> entryClasses, long seed, int maxIterations) {
        List<String> fqns = new ArrayList<>(graph.classes().keySet());
        Map<String, Set<String>> out = new HashMap<>();
        Map<String, Set<String>> in = new HashMap<>();
        for (String fqn : fqns) {
            out.put(fqn, new TreeSet<>());
            in.put(fqn, new TreeSet<>());
        }
        for (ClassEdge edge : graph.edges()) {
            out.get(edge.fromFqn()).add(edge.toFqn());
            in.get(edge.toFqn()).add(edge.fromFqn());
        }
        Map<String, Community> communities = communities(fqns, out, in, seed, maxIterations);
        Map<String, Integer> dependents = dependents(fqns, out);
        Map<String, ClassMetrics> metrics = new TreeMap<>();
        for (String fqn : fqns) {
            Community community = communities.get(fqn);
            metrics.put(fqn, new ClassMetrics(in.get(fqn).size(), out.get(fqn).size(), dependents.get(fqn),
                    entryClasses.contains(fqn), community.id(), community.label()));
        }
        return new Analysis(metrics, cycles(graph));
    }

    /**
     * Per class, the repository classes that reach it through usages, transitively, itself excluded. Strongly
     * connected components are condensed first: a class gets the rest of its own component plus every class of the
     * components that reach its component, found by a reverse walk over the condensation with a reused stamp array.
     */
    private static Map<String, Integer> dependents(List<String> fqns, Map<String, Set<String>> out) {
        int n = fqns.size();
        Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < n; i++) {
            ids.put(fqns.get(i), i);
        }
        int[][] adjacency = new int[n][];
        for (int i = 0; i < n; i++) {
            adjacency[i] = out.get(fqns.get(i)).stream().mapToInt(ids::get).toArray();
        }
        int[] component = new int[n];
        int count = components(adjacency, component);
        int[] size = new int[count];
        for (int c : component) {
            size[c]++;
        }
        int[] predecessorCount = new int[count];
        for (int u = 0; u < n; u++) {
            for (int v : adjacency[u]) {
                if (component[u] != component[v]) {
                    predecessorCount[component[v]]++;
                }
            }
        }
        int[][] predecessors = new int[count][];
        for (int c = 0; c < count; c++) {
            predecessors[c] = new int[predecessorCount[c]];
            predecessorCount[c] = 0;
        }
        for (int u = 0; u < n; u++) {
            for (int v : adjacency[u]) {
                if (component[u] != component[v]) {
                    predecessors[component[v]][predecessorCount[component[v]]++] = component[u];
                }
            }
        }
        int[] reaching = new int[count];
        int[] stamp = new int[count];
        int[] queue = new int[count];
        for (int c = 0; c < count; c++) {
            int mark = c + 1;
            int head = 0;
            int tail = 0;
            stamp[c] = mark;
            queue[tail++] = c;
            int classes = 0;
            while (head < tail) {
                int current = queue[head++];
                for (int pred : predecessors[current]) {
                    if (stamp[pred] != mark) {
                        stamp[pred] = mark;
                        classes += size[pred];
                        queue[tail++] = pred;
                    }
                }
            }
            reaching[c] = classes;
        }
        Map<String, Integer> dependents = new HashMap<>();
        for (int i = 0; i < n; i++) {
            dependents.put(fqns.get(i), size[component[i]] - 1 + reaching[component[i]]);
        }
        return dependents;
    }

    /** Iterative Tarjan: fills {@code component} and returns the component count. */
    private static int components(int[][] adjacency, int[] component) {
        int n = adjacency.length;
        int[] index = new int[n];
        int[] low = new int[n];
        int[] next = new int[n];
        boolean[] onStack = new boolean[n];
        int[] stack = new int[n];
        int[] call = new int[n];
        Arrays.fill(index, -1);
        int counter = 0;
        int top = 0;
        int count = 0;
        for (int root = 0; root < n; root++) {
            if (index[root] != -1) {
                continue;
            }
            int depth = 0;
            call[depth++] = root;
            index[root] = low[root] = counter++;
            stack[top++] = root;
            onStack[root] = true;
            while (depth > 0) {
                int u = call[depth - 1];
                if (next[u] < adjacency[u].length) {
                    int v = adjacency[u][next[u]++];
                    if (index[v] == -1) {
                        index[v] = low[v] = counter++;
                        stack[top++] = v;
                        onStack[v] = true;
                        call[depth++] = v;
                    } else if (onStack[v]) {
                        low[u] = Math.min(low[u], index[v]);
                    }
                } else {
                    depth--;
                    if (low[u] == index[u]) {
                        int w;
                        do {
                            w = stack[--top];
                            onStack[w] = false;
                            component[w] = count;
                        } while (w != u);
                        count++;
                    }
                    if (depth > 0) {
                        int parent = call[depth - 1];
                        low[parent] = Math.min(low[parent], low[u]);
                    }
                }
            }
        }
        return count;
    }

    /**
     * Label propagation communities, numbered by size then smallest member. A community is labelled by its dominant
     * package (spec §9.2); when several communities share that package, each label also names the community's most
     * used class (most incoming usages, then smallest FQN) relative to that package. A class belongs to one community,
     * so no two labels are the same.
     */
    private static Map<String, Community> communities(List<String> fqns, Map<String, Set<String>> out,
            Map<String, Set<String>> in, long seed, int maxIterations) {
        if (fqns.isEmpty()) {
            return Map.of();
        }
        Graph<String, DefaultEdge> undirected = new DefaultUndirectedGraph<>(DefaultEdge.class);
        fqns.forEach(undirected::addVertex);
        for (String from : fqns) {
            for (String to : out.get(from)) {
                if (!undirected.containsEdge(from, to)) {
                    undirected.addEdge(from, to);
                }
            }
        }
        List<List<String>> clusters = new LabelPropagationClustering<>(undirected, maxIterations, new Random(seed))
                .getClustering().getClusters().stream()
                .map(cluster -> cluster.stream().sorted().toList())
                .sorted(Comparator.comparingInt((List<String> cluster) -> -cluster.size())
                        .thenComparing(cluster -> cluster.getFirst()))
                .toList();
        List<String> packages = clusters.stream().map(RepoGraphAnalyzer::dominantPackage).toList();
        Map<String, Long> sharing = packages.stream()
                .collect(Collectors.groupingBy(name -> name, Collectors.counting()));
        Map<String, Community> byClass = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            String label = sharing.get(packages.get(i)) > 1
                    ? packages.get(i) + LABEL_SEPARATOR + relativeName(mostUsed(clusters.get(i), in), packages.get(i))
                    : packages.get(i);
            Community community = new Community(i + 1, label);
            clusters.get(i).forEach(fqn -> byClass.put(fqn, community));
        }
        return byClass;
    }

    /** The member with the most incoming usages; members are sorted, so a tie keeps the smallest FQN. */
    private static String mostUsed(List<String> members, Map<String, Set<String>> in) {
        String best = members.getFirst();
        for (String fqn : members) {
            if (in.get(fqn).size() > in.get(best).size()) {
                best = fqn;
            }
        }
        return best;
    }

    /** The FQN without the given package prefix (`p.x.Foo` in `p` is `x.Foo`); the whole FQN outside it. */
    private static String relativeName(String fqn, String packageName) {
        return fqn.startsWith(packageName + ".") ? fqn.substring(packageName.length() + 1) : fqn;
    }

    private static String dominantPackage(List<String> members) {
        Map<String, Integer> counts = new TreeMap<>();
        members.forEach(fqn -> counts.merge(ClassGraph.packageOf(fqn), 1, Integer::sum));
        String best = null;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (best == null || entry.getValue() > counts.get(best)) {
                best = entry.getKey();
            }
        }
        return best.isEmpty() ? DEFAULT_PACKAGE : best;
    }

    private static List<List<String>> cycles(ClassGraph graph) {
        Graph<String, DefaultEdge> packages = new DefaultDirectedGraph<>(DefaultEdge.class);
        graph.classes().values().forEach(node -> packages.addVertex(node.packageName()));
        for (ClassEdge edge : graph.edges()) {
            String from = ClassGraph.packageOf(edge.fromFqn());
            String to = ClassGraph.packageOf(edge.toFqn());
            if (!from.equals(to) && !packages.containsEdge(from, to)) {
                packages.addEdge(from, to);
            }
        }
        return new KosarajuStrongConnectivityInspector<>(packages).stronglyConnectedSets().stream()
                .filter(component -> component.size() > 1)
                .map(component -> component.stream().map(name -> name.isEmpty() ? DEFAULT_PACKAGE : name).sorted().toList())
                .sorted(Comparator.comparing((List<String> cycle) -> cycle.getFirst()))
                .toList();
    }
}
