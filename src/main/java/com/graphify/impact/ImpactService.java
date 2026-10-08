package com.graphify.impact;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.ReadSnapshot;
import org.springframework.stereotype.Service;

/**
 * Runs the impact engine with limits from AppSettings and rules/labels from the database, in one read-only snapshot
 * so an index run committing meanwhile cannot produce a half-old, half-new graph.
 */
@Service
public class ImpactService {

    private final JdbcImpactGraph graph;
    private final ImpactRules rules;
    private final VersionWarnings versionWarnings;
    private final ReadSnapshot snapshot;
    private final AppSettings settings;

    public ImpactService(JdbcImpactGraph graph, ImpactRules rules, VersionWarnings versionWarnings,
            ReadSnapshot snapshot, AppSettings settings) {
        this.graph = graph;
        this.rules = rules;
        this.versionWarnings = versionWarnings;
        this.snapshot = snapshot;
        this.settings = settings;
    }

    public ImpactResult analyze(ImpactRequest request) {
        ImpactLimits limits = new ImpactLimits(
                settings.getInt(SettingKeys.IMPACT_DEFAULT_DEPTH),
                settings.getInt(SettingKeys.IMPACT_MAX_DEPTH),
                settings.getInt(SettingKeys.IMPACT_MAX_RESULTS));
        return snapshot.read(() -> {
            ImpactResult result = new ImpactEngine(graph).analyze(request, limits, rules.rules(),
                    rules.entryPointLabels());
            return result.withVersionWarnings(versionWarnings.find(request.symbolIds(), result.staleness()));
        });
    }
}
