package com.graphify.repograph;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A repository's class graph: its declared types (sorted by FQN), usages between them and, optionally, outside. */
public record ClassGraph(long repositoryId, Map<String, ClassNode> classes, List<ClassEdge> edges,
        List<ExternalEdge> external) {

    public ClassGraph {
        classes = Collections.unmodifiableMap(new TreeMap<>(classes));
        edges = List.copyOf(edges);
        external = List.copyOf(external);
    }

    /** The package of a type key: everything before the last dot ({@code a.b.C$D} is in {@code a.b}). */
    public static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /** External type FQN to the group it is shown in. */
    public Map<String, ExternalGroup> externalGroups() {
        Map<String, ExternalGroup> groups = new HashMap<>();
        for (ExternalEdge edge : external) {
            groups.putIfAbsent(edge.toFqn(), edge.group());
        }
        return groups;
    }
}
