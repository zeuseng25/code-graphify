package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.repository.RepositoryRef;
import java.util.List;
import java.util.Map;

/**
 * The headline numbers of spec §5.6; {@code partialClasspathRepositories} have modules indexed without a full
 * classpath (sorted by project key, slug, id). {@code nodesByConfidence} counts AFFECTED nodes; both confidence maps list every confidence, zeros too.
 */
public record ImpactSummary(
        int repositories,
        int modules,
        int classes,
        int methods,
        int usages,
        Map<Integer, Integer> usagesByLevel,
        Map<Confidence, Integer> usagesByConfidence,
        Map<Confidence, Integer> nodesByConfidence,
        List<RepositoryRef> partialClasspathRepositories) {
}
