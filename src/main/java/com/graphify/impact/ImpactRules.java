package com.graphify.impact;

import com.graphify.indexer.model.UsageKind;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads impact_relation_rule and the enabled rows of entry_point_annotation (spec §6.2). */
@Repository
public class ImpactRules {

    private final JdbcTemplate jdbc;

    public ImpactRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UsageKind, ImpactRule> rules() {
        Map<UsageKind, ImpactRule> rules = new EnumMap<>(UsageKind.class);
        jdbc.query("SELECT usage_kind, propagates, shown_at_level1 FROM impact_relation_rule", rs -> {
            UsageKind kind = UsageKind.valueOf(rs.getString("usage_kind"));
            rules.put(kind, new ImpactRule(kind, rs.getInt("propagates") == 1, rs.getInt("shown_at_level1") == 1));
        });
        return rules;
    }

    public Map<String, String> entryPointLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        jdbc.query("SELECT annotation_fqn, label FROM entry_point_annotation WHERE enabled = 1 ORDER BY annotation_fqn",
                rs -> {
                    labels.put(rs.getString("annotation_fqn"), rs.getString("label"));
                });
        return labels;
    }
}
