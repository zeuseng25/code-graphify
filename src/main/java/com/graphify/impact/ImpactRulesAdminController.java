package com.graphify.impact;

import com.graphify.indexer.model.UsageKind;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entry-point annotations and impact rules (spec §10.6 "Giriş noktası annotation'ları", "Etki kuralları"). */
@RestController
@RequestMapping("/api/v1/admin")
public class ImpactRulesAdminController {

    private final ImpactRuleAdministration administration;

    public ImpactRulesAdminController(ImpactRuleAdministration administration) {
        this.administration = administration;
    }

    @GetMapping("/entry-point-annotations")
    public List<EntryPointAnnotation> annotations() {
        return administration.annotations();
    }

    @PostMapping("/entry-point-annotations")
    public ResponseEntity<EntryPointAnnotation> create(
            @RequestBody(required = false) ImpactRuleAdministration.AnnotationChange change,
            Authentication authentication) {
        EntryPointAnnotation created = administration.create(change, authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/admin/entry-point-annotations/" + created.id())).body(created);
    }

    @PutMapping("/entry-point-annotations/{id}")
    public EntryPointAnnotation update(@PathVariable long id,
            @RequestBody(required = false) ImpactRuleAdministration.AnnotationChange change,
            Authentication authentication) {
        return administration.update(id, change, authentication.getName());
    }

    @DeleteMapping("/entry-point-annotations/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
        administration.delete(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/impact-rules")
    public List<ImpactRule> rules() {
        return administration.rules();
    }

    @PutMapping("/impact-rules/{kind}")
    public ImpactRule updateRule(@PathVariable UsageKind kind,
            @RequestBody(required = false) ImpactRuleAdministration.RuleChange change, Authentication authentication) {
        return administration.updateRule(kind, change, authentication.getName());
    }
}
