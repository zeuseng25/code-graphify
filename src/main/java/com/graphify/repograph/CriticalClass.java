package com.graphify.repograph;

/** A class ranked by how much of the repository depends on it (spec §9.2 "Kritik sınıflar"). */
public record CriticalClass(long symbolId, String fqn, int inDegree, int outDegree, int dependents,
        boolean entryPoint) {
}
