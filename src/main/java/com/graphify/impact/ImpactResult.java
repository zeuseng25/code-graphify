package com.graphify.impact;

import java.util.List;

/**
 * The impact response of spec §10.4. {@code summary.usagesByLevel} stands in for the spec's {@code levels[]};
 * {@code staleness} is the index freshness of every module an edge lies in; {@code versionWarnings} comes from
 * {@code MODULE_DEPENDENCY} ({@code VersionWarnings}).
 */
public record ImpactResult(
        ImpactSummary summary,
        List<ImpactNode> nodes,
        List<ImpactEdge> edges,
        List<EntryPoint> entryPoints,
        List<RepoState> staleness,
        List<VersionWarning> versionWarnings,
        boolean truncated) {

    public ImpactResult withVersionWarnings(List<VersionWarning> warnings) {
        return new ImpactResult(summary, nodes, edges, entryPoints, staleness, List.copyOf(warnings), truncated);
    }
}
