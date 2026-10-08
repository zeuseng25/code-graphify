package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the impact engine reads from the index. Implementations must accept collections of any size.
 * {@link #descendants} and {@link #symbols} should return results in a stable order (by id); the engine itself
 * preserves the request order of the target ids.
 */
public interface ImpactGraph {

    Map<Long, ImpactSymbol> symbols(Collection<Long> ids);

    /** All symbols whose parent chain leads to {@code typeId} (members, nested types and their members). */
    List<Long> descendants(long typeId);

    /** The ids of those keys that exist; absent keys are left out. */
    Map<String, Long> idsOfKeys(Collection<String> keys);

    List<ImpactUsage> usagesTo(Collection<Long> targetIds, Set<UsageKind> kinds, Set<Confidence> confidences);

    /** File, position and snippet of the given usages; called once per analysis, for the kept edges only. */
    Map<Long, UsageDetail> usageDetails(Collection<Long> usageIds);

    /** For each given symbol, the methods it overrides (its OVERRIDES usages). */
    Map<Long, List<Long>> overriddenMethods(Collection<Long> symbolIds);

    /** For each given method, the methods that override it (the {@code from} side of its OVERRIDES usages). */
    Map<Long, List<Long>> overriders(Collection<Long> methodIds);

    List<AnnotationUse> annotationsOn(Collection<Long> symbolIds, Collection<String> annotationKeys);

    /** For each given symbol, the modules it is declared in (by module id). */
    Map<Long, List<Long>> declaringModules(Collection<Long> symbolIds);

    Map<Long, RepoState> repoStates(Collection<Long> moduleIds);
}
