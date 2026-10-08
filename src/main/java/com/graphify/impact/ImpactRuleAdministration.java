package com.graphify.impact;

import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.common.util.Utf8;
import com.graphify.indexer.model.UsageKind;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Entry-point annotations and impact relation rules for admins (spec §10.6); read afresh by every analysis. */
@Service
public class ImpactRuleAdministration {

    public record AnnotationChange(String annotationFqn, String label, Boolean enabled) {
    }

    public record RuleChange(Boolean propagates, Boolean shownAtLevel1) {
    }

    /** A dotted Java type name, the form annotation_fqn is matched against. */
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** Widths of entry_point_annotation.annotation_fqn and .label in V3__search_and_impact.sql. */
    private static final int FQN_BYTES = 500;
    private static final int LABEL_BYTES = 200;

    private final JdbcTemplate jdbc;
    private final ImpactRules rules;
    private final AuditLog auditLog;

    public ImpactRuleAdministration(JdbcTemplate jdbc, ImpactRules rules, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.auditLog = auditLog;
    }

    public List<EntryPointAnnotation> annotations() {
        return jdbc.query("SELECT id, annotation_fqn, label, enabled FROM entry_point_annotation ORDER BY annotation_fqn",
                (rs, row) -> new EntryPointAnnotation(rs.getLong("id"), rs.getString("annotation_fqn"),
                        rs.getString("label"), rs.getInt("enabled") == 1));
    }

    public EntryPointAnnotation annotation(long id) {
        return annotations().stream().filter(a -> a.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("No entry-point annotation with id " + id));
    }

    @Transactional
    public EntryPointAnnotation create(AnnotationChange change, String actor) {
        AnnotationChange valid = validate(change);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO entry_point_annotation "
                        + "(annotation_fqn, label, enabled) VALUES (?, ?, ?)", new String[] {"id"});
                statement.setString(1, valid.annotationFqn());
                statement.setString(2, valid.label());
                statement.setInt(3, valid.enabled() ? 1 : 0);
                return statement;
            }, keys);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Annotation " + valid.annotationFqn() + " is already listed");
        }
        auditLog.record(actor, "ENTRY_POINT_ANNOTATION_CREATED", valid.annotationFqn(), valid.label());
        return annotation(keys.getKey().longValue());
    }

    @Transactional
    public EntryPointAnnotation update(long id, AnnotationChange change, String actor) {
        annotation(id);
        AnnotationChange valid = validate(change);
        try {
            jdbc.update("UPDATE entry_point_annotation SET annotation_fqn = ?, label = ?, enabled = ? WHERE id = ?",
                    valid.annotationFqn(), valid.label(), valid.enabled() ? 1 : 0, id);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Annotation " + valid.annotationFqn() + " is already listed");
        }
        auditLog.record(actor, "ENTRY_POINT_ANNOTATION_UPDATED", valid.annotationFqn(),
                "label=" + valid.label() + ", enabled=" + valid.enabled());
        return annotation(id);
    }

    @Transactional
    public void delete(long id, String actor) {
        EntryPointAnnotation current = annotation(id);
        jdbc.update("DELETE FROM entry_point_annotation WHERE id = ?", id);
        auditLog.record(actor, "ENTRY_POINT_ANNOTATION_DELETED", current.annotationFqn(), null);
    }

    public List<ImpactRule> rules() {
        return rules.rules().values().stream().sorted(Comparator.comparing(ImpactRule::kind)).toList();
    }

    @Transactional
    public ImpactRule updateRule(UsageKind kind, RuleChange change, String actor) {
        if (change == null || change.propagates() == null || change.shownAtLevel1() == null) {
            throw new InvalidRequestException("A body {propagates, shownAtLevel1} is required");
        }
        int updated = jdbc.update("UPDATE impact_relation_rule SET propagates = ?, shown_at_level1 = ? "
                + "WHERE usage_kind = ?", change.propagates() ? 1 : 0, change.shownAtLevel1() ? 1 : 0, kind.name());
        if (updated == 0) {
            throw new NotFoundException("No impact rule for " + kind);
        }
        auditLog.record(actor, "IMPACT_RULE_UPDATED", kind.name(),
                "propagates=" + change.propagates() + ", shownAtLevel1=" + change.shownAtLevel1());
        return rules.rules().get(kind);
    }

    private static AnnotationChange validate(AnnotationChange change) {
        if (change == null || change.enabled() == null) {
            throw new InvalidRequestException("A body {annotationFqn, label, enabled} is required");
        }
        String fqn = change.annotationFqn() == null ? "" : change.annotationFqn().strip();
        if (!JAVA_NAME.matcher(fqn).matches() || Utf8.byteLength(fqn) > FQN_BYTES) {
            throw new InvalidRequestException("annotationFqn must be a fully qualified Java name");
        }
        String label = change.label() == null ? "" : change.label().strip();
        if (label.isEmpty() || Utf8.byteLength(label) > LABEL_BYTES) {
            throw new InvalidRequestException("label is required and at most " + LABEL_BYTES + " bytes");
        }
        return new AnnotationChange(fqn, label, change.enabled());
    }
}
