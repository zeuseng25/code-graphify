package com.graphify.maven;

import com.graphify.audit.AuditAction;
import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.common.secret.SecretUpdate;
import com.graphify.common.util.Utf8;
import com.graphify.scm.UrlMasking;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maven artifact repositories for admins; secrets are never returned or audited. */
@Service
public class ArtifactRepositoryAdministration {

    /** Widths of the artifact_repository columns in V4__repository_acquisition.sql. */
    private static final int NAME_BYTES = 200;
    private static final int URL_BYTES = 1000;
    private static final int USERNAME_BYTES = 200;
    private static final int MIRROR_BYTES = 200;

    /** Plaintext cap so the AES-GCM ciphertext fits artifact_repository.secret_enc (VARCHAR2(4000 BYTE)). */
    private static final int SECRET_BYTES = 2000;

    /** Schemes a Maven repository URL may use: a remote server or a local directory (file:). */
    private static final List<String> SCHEMES = List.of("http", "https", "file");

    private static final String SUCCESS = "SUCCESS";
    private static final String AUTH_FAILED = "AUTH_FAILED";
    private static final String FAILED = "FAILED";

    private final ArtifactRepositories repositories;
    private final ArtifactRepositoryProbe probe;
    private final AuditLog auditLog;

    public ArtifactRepositoryAdministration(ArtifactRepositories repositories, ArtifactRepositoryProbe probe,
            AuditLog auditLog) {
        this.repositories = repositories;
        this.probe = probe;
        this.auditLog = auditLog;
    }

    public List<ArtifactRepositoryView> list() {
        return repositories.views();
    }

    public ArtifactRepositoryView get(long id) {
        return repositories.view(id)
                .orElseThrow(() -> new NotFoundException("No artifact repository with id " + id));
    }

    @Transactional
    public ArtifactRepositoryView create(ArtifactRepositoryUpdate update, String actor) {
        Valid valid = validate(update);
        String secret = SecretUpdate.resolve(update.secret(), null, true, SECRET_BYTES, "password", "url or username");
        long id;
        try {
            id = repositories.insert(valid.name(), valid.url(), valid.username(), secret, valid.mirrorOf(),
                    valid.sortOrder(), valid.enabled());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("An artifact repository named " + valid.name() + " already exists");
        }
        auditLog.record(actor, AuditAction.ARTIFACT_REPOSITORY_CREATED, valid.name(), null);
        return get(id);
    }

    @Transactional
    public ArtifactRepositoryView update(long id, ArtifactRepositoryUpdate update, String actor) {
        ArtifactRepository current = repositories.find(id)
                .orElseThrow(() -> new NotFoundException("No artifact repository with id " + id));
        ArtifactRepositoryView before = get(id);
        Valid valid = validate(update);
        String secret = SecretUpdate.resolve(update.secret(), current.secret(),
                Objects.equals(valid.url(), current.url()) && Objects.equals(valid.username(), current.username()),
                SECRET_BYTES, "password", "url or username");
        try {
            repositories.update(id, valid.name(), valid.url(), valid.username(), secret, valid.mirrorOf(),
                    valid.sortOrder(), valid.enabled());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("An artifact repository named " + valid.name() + " already exists");
        }
        auditLog.record(actor, AuditAction.ARTIFACT_REPOSITORY_UPDATED, valid.name(),
                "changed: " + changed(before, valid, !Objects.equals(secret, current.secret())));
        return get(id);
    }

    /** Nothing references artifact repositories, so a delete needs no usage check. */
    @Transactional
    public void delete(long id, String actor) {
        ArtifactRepositoryView current = get(id);
        repositories.delete(id);
        auditLog.record(actor, AuditAction.ARTIFACT_REPOSITORY_DELETED, current.name(), null);
    }

    /** Not transactional: the test result is stored even when the test fails. */
    public void test(long id) {
        ArtifactRepository repository = repositories.find(id)
                .orElseThrow(() -> new NotFoundException("No artifact repository with id " + id));
        try {
            probe.test(repository);
            repositories.recordTest(id, SUCCESS);
        } catch (ArtifactRepositoryProbe.ArtifactAuthenticationException e) {
            repositories.recordTest(id, AUTH_FAILED);
            throw new ExternalSystemException(UrlMasking.mask(e.getMessage()));
        } catch (ExternalSystemException e) {
            repositories.recordTest(id, FAILED);
            throw new ExternalSystemException(UrlMasking.mask(e.getMessage()));
        } catch (RuntimeException e) {
            // e.g. a URL the HTTP client or file system refuses: still a failed test, never a 500
            repositories.recordTest(id, FAILED);
            throw new ExternalSystemException("The repository test failed: " + UrlMasking.mask(e.getMessage()));
        }
    }

    private record Valid(String name, String url, String username, String mirrorOf, int sortOrder, boolean enabled) {
    }

    private static Valid validate(ArtifactRepositoryUpdate update) {
        if (update == null) {
            throw new InvalidRequestException("A repository document is required");
        }
        String name = required("name", update.name(), NAME_BYTES);
        String url = required("url", update.url(), URL_BYTES);
        requireScheme(url);
        String username = optional("username", update.username(), USERNAME_BYTES);
        String mirrorOf = optional("mirrorOf", update.mirrorOf(), MIRROR_BYTES);
        if (update.sortOrder() == null || update.sortOrder() < 0) {
            throw new InvalidRequestException("sortOrder is required and must not be negative");
        }
        if (update.enabled() == null) {
            throw new InvalidRequestException("enabled is required");
        }
        return new Valid(name, url, username, mirrorOf, update.sortOrder(), update.enabled());
    }

    private static String required(String field, String value, int maxBytes) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        return optional(field, value, maxBytes);
    }

    private static String optional(String field, String value, int maxBytes) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (Utf8.byteLength(stripped) > maxBytes) {
            throw new InvalidRequestException(field + " is longer than " + maxBytes + " bytes");
        }
        return stripped;
    }

    private static void requireScheme(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                throw new InvalidRequestException("url must start with http://, https:// or file:");
            }
            if (uri.getUserInfo() != null) {
                throw new InvalidRequestException("url must not contain credentials; use username and secret");
            }
            if (uri.isOpaque()) {
                throw new InvalidRequestException("url must be hierarchical, e.g. https://repo.example.com/maven");
            }
            if (!"file".equalsIgnoreCase(scheme) && uri.getHost() == null) {
                throw new InvalidRequestException("url must name a host, e.g. https://repo.example.com/maven");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new InvalidRequestException("url must not contain a query or fragment");
            }
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("url is not a valid URI");
        }
    }

    private static String changed(ArtifactRepositoryView before, Valid after, boolean secretChanged) {
        List<String> fields = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            fields.add("name (was " + before.name() + ")");
        }
        if (!before.url().equals(after.url())) {
            fields.add("url");
        }
        if (!Objects.equals(before.username(), after.username())) {
            fields.add("username");
        }
        if (secretChanged) {
            fields.add("secret");
        }
        if (!Objects.equals(before.mirrorOf(), after.mirrorOf())) {
            fields.add("mirrorOf");
        }
        if (before.sortOrder() != after.sortOrder()) {
            fields.add("sortOrder");
        }
        if (before.enabled() != after.enabled()) {
            fields.add("enabled");
        }
        return fields.isEmpty() ? "nothing" : String.join(", ", fields);
    }
}
