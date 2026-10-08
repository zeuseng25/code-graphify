package com.graphify.repograph;

import com.graphify.indexer.model.UsageKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The graph-repo fixture as an in-memory class graph (symbol ids 1..7), plus one external library use. */
final class SampleGraphs {

    static final ExternalGroup LIB = new ExternalGroup("lib:com.lib:util:1.0", "com.lib:util:1.0",
            NodeType.EXTERNAL_LIBRARY);

    private SampleGraphs() {
    }

    static ClassGraph sample() {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        add(classes, 1, "com.g.a.Alpha", "core");
        add(classes, 2, "com.g.a.AlphaHelper", "core");
        add(classes, 3, "com.g.b.Beta", "core");
        add(classes, 4, "com.g.c.Gamma", "app");
        add(classes, 5, "com.g.c.Delta", "app");
        add(classes, 6, "com.g.web.Endpoint", "app");
        add(classes, 7, "com.g.web.Api", "app");
        List<ClassEdge> edges = List.of(
                new ClassEdge("com.g.a.Alpha", "com.g.b.Beta", UsageKind.TYPE_REF, 1),
                new ClassEdge("com.g.a.Alpha", "com.g.b.Beta", UsageKind.INSTANTIATION, 1),
                new ClassEdge("com.g.a.Alpha", "com.g.b.Beta", UsageKind.CALL, 1),
                new ClassEdge("com.g.a.Alpha", "com.g.a.AlphaHelper", UsageKind.CALL, 1),
                new ClassEdge("com.g.b.Beta", "com.g.a.AlphaHelper", UsageKind.CALL, 1),
                new ClassEdge("com.g.c.Gamma", "com.g.a.Alpha", UsageKind.INSTANTIATION, 1),
                new ClassEdge("com.g.c.Gamma", "com.g.a.Alpha", UsageKind.CALL, 1),
                new ClassEdge("com.g.c.Gamma", "com.g.c.Delta", UsageKind.CALL, 1),
                new ClassEdge("com.g.c.Delta", "com.g.c.Gamma", UsageKind.INSTANTIATION, 1),
                new ClassEdge("com.g.web.Api", "com.g.web.Endpoint", UsageKind.ANNOTATION, 1),
                new ClassEdge("com.g.web.Api", "com.g.c.Gamma", UsageKind.CALL, 1));
        List<ExternalEdge> external = List.of(
                new ExternalEdge("com.g.a.Alpha", LIB, "com.lib.Strings", UsageKind.CALL, 2),
                new ExternalEdge("com.g.b.Beta", LIB, "com.lib.Numbers", UsageKind.CALL, 1));
        return new ClassGraph(1, classes, edges, external);
    }

    private static void add(Map<String, ClassNode> classes, long id, String fqn, String module) {
        classes.put(fqn, new ClassNode(id, fqn, ClassGraph.packageOf(fqn), module));
    }
}
