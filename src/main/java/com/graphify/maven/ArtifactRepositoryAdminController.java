package com.graphify.maven;

import java.net.URI;
import java.util.List;
import java.util.Map;
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

/** Maven artifact repositories for admins. */
@RestController
@RequestMapping("/api/v1/admin/artifact-repositories")
public class ArtifactRepositoryAdminController {

    private final ArtifactRepositoryAdministration administration;

    public ArtifactRepositoryAdminController(ArtifactRepositoryAdministration administration) {
        this.administration = administration;
    }

    @GetMapping
    public List<ArtifactRepositoryView> list() {
        return administration.list();
    }

    @PostMapping
    public ResponseEntity<ArtifactRepositoryView> create(@RequestBody(required = false) ArtifactRepositoryUpdate update,
            Authentication authentication) {
        ArtifactRepositoryView created = administration.create(update, authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/admin/artifact-repositories/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ArtifactRepositoryView get(@PathVariable long id) {
        return administration.get(id);
    }

    @PutMapping("/{id}")
    public ArtifactRepositoryView update(@PathVariable long id, @RequestBody(required = false) ArtifactRepositoryUpdate update,
            Authentication authentication) {
        return administration.update(id, update, authentication.getName());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
        administration.delete(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public Map<String, Boolean> test(@PathVariable long id) {
        administration.test(id);
        return Map.of("ok", true);
    }
}
