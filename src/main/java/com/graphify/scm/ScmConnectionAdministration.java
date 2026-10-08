package com.graphify.scm;

import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.common.secret.SecretUpdate;
import com.graphify.common.util.Utf8;
import com.graphify.indexing.IndexLock;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Repo connections (Bitbucket, GitHub, Git) for admins (spec §10.6 "SCM bağlantıları"); secrets are never returned or audited. */
@Service
public class ScmConnectionAdministration {

    /** Widths of the scm_connection columns in V1__core_schema.sql. */
    private static final int NAME_BYTES = 200;
    private static final int URL_BYTES = 1000;
    private static final int USERNAME_BYTES = 200;
    private static final int LIST_BYTES = 4000;

    /** Plaintext cap so the AES-GCM ciphertext fits scm_connection.secret_enc (VARCHAR2(4000 BYTE)). */
    private static final int SECRET_BYTES = 2000;

    /** Schemes an SCM REST base URL may use. */
    private static final List<String> SCHEMES = List.of("http", "https");

    private final ScmConnections connections;
    private final List<ScmClient> clients;
    private final AuditLog auditLog;
    private final IndexLock indexLock;
    private final TransactionTemplate transaction;

    public ScmConnectionAdministration(ScmConnections connections, List<ScmClient> clients, AuditLog auditLog,
            IndexLock indexLock, PlatformTransactionManager transactionManager) {
        this.connections = connections;
        this.clients = clients;
        this.auditLog = auditLog;
        this.indexLock = indexLock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<ScmConnectionView> list() {
        return connections.views();
    }

    public ScmConnectionView get(long id) {
        return connections.view(id).orElseThrow(() -> new NotFoundException("No SCM connection with id " + id));
    }

    @Transactional
    public ScmConnectionView create(ScmConnectionUpdate update, String actor) {
        Valid valid = validate(update);
        String secret = SecretUpdate.resolve(update.secret(), null, true, SECRET_BYTES, "token", "baseUrl or username");
        requireGitHubToken(valid.type(), secret);
        long id;
        try {
            id = connections.insert(valid.name(), valid.type(), valid.baseUrl(), valid.username(), secret,
                    valid.includeProjects(), valid.excludeRepos(), valid.repositoryUrls(),
                    valid.includeOwnRepositories(), valid.enabled());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("An SCM connection named " + valid.name() + " already exists");
        }
        auditLog.record(actor, "SCM_CONNECTION_CREATED", valid.name(), valid.type().name());
        return get(id);
    }

    /**
     * Not transactional itself: a baseUrl change takes the index lock before its transaction starts and releases it
     * after the commit, so no sync or index run is mid-flight when clone URLs are reset (a running sync would
     * otherwise re-activate rows under the old host after the deactivation).
     */
    public ScmConnectionView update(long id, ScmConnectionUpdate update, String actor) {
        ScmConnection current = connections.find(id)
                .orElseThrow(() -> new NotFoundException("No SCM connection with id " + id));
        Valid valid = validate(update);
        if (valid.type() != current.type()) {
            throw new InvalidRequestException("type cannot be changed; create a new connection");
        }
        if (Objects.equals(valid.baseUrl(), current.baseUrl())) {
            return transaction.execute(status -> applyUpdate(id, valid, update.secret(), actor, current.baseUrl()));
        }
        if (!indexLock.tryAcquire(IndexLock.REPOINT_HOLDER)) {
            throw new ConflictException("An index run or cleanup is in progress; change baseUrl after it finishes");
        }
        try {
            return transaction.execute(status -> applyUpdate(id, valid, update.secret(), actor, current.baseUrl()));
        } finally {
            indexLock.release(IndexLock.REPOINT_HOLDER);
        }
    }

    /** @param comparedBaseUrl the stored baseUrl the caller compared against; a different one now means a concurrent change */
    private ScmConnectionView applyUpdate(long id, Valid valid, String newSecret, String actor,
            String comparedBaseUrl) {
        connections.lock(id);
        ScmConnection current = connections.find(id)
                .orElseThrow(() -> new NotFoundException("No SCM connection with id " + id));
        if (!comparedBaseUrl.equals(current.baseUrl())) {
            throw new ConflictException("The connection was changed concurrently; retry");
        }
        ScmConnectionView before = get(id);
        String secret = SecretUpdate.resolve(newSecret, current.secret(),
                Objects.equals(valid.baseUrl(), current.baseUrl()) && Objects.equals(valid.username(), current.username()),
                SECRET_BYTES, "token", "baseUrl or username");
        requireGitHubToken(valid.type(), secret);
        try {
            connections.update(id, valid.name(), valid.type(), valid.baseUrl(), valid.username(), secret,
                    valid.includeProjects(), valid.excludeRepos(), valid.repositoryUrls(),
                    valid.includeOwnRepositories(), valid.enabled());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("An SCM connection named " + valid.name() + " already exists");
        }
        boolean repointed = !Objects.equals(valid.baseUrl(), current.baseUrl());
        if (repointed) {
            // stored clone URLs still name the old host; never let the new credentials be sent there
            connections.deactivateRepositories(id);
        }
        auditLog.record(actor, "SCM_CONNECTION_UPDATED", valid.name(),
                "changed: " + changed(before, valid, !Objects.equals(secret, current.secret()))
                        + (repointed ? "; repositories deactivated until the next sync" : ""));
        return get(id);
    }

    @Transactional
    public void delete(long id, String actor) {
        ScmConnectionView current = get(id);
        connections.lock(id);
        if (connections.repositoryCount(id) > 0) {
            throw new ConflictException("SCM connection " + current.name()
                    + " still has repositories and run history; disable it instead");
        }
        try {
            connections.delete(id);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("SCM connection " + current.name()
                    + " still has repositories and run history; disable it instead");
        }
        auditLog.record(actor, "SCM_CONNECTION_DELETED", current.name(), null);
    }

    /** Not transactional: the test result is stored even when the test fails. */
    public void test(long id) {
        ScmConnection connection = connections.find(id)
                .orElseThrow(() -> new NotFoundException("No SCM connection with id " + id));
        ScmClient client = clients.stream().filter(c -> c.type() == connection.type()).findFirst()
                .orElseThrow(() -> new ExternalSystemException("No SCM client for " + connection.type()
                        + " is available; this connection type cannot be tested yet"));
        try {
            client.test(connection);
            connections.recordTest(id, ConnectionSyncStatus.SUCCESS);
        } catch (ScmAuthenticationException e) {
            connections.recordTest(id, ConnectionSyncStatus.AUTH_FAILED);
            throw new ExternalSystemException(UrlMasking.mask(e.getMessage()));
        } catch (ScmException e) {
            connections.recordTest(id, ConnectionSyncStatus.FAILED);
            throw new ExternalSystemException(UrlMasking.mask(e.getMessage()));
        } catch (RuntimeException e) {
            // e.g. a URL the HTTP client refuses: still a failed test, never a 500
            connections.recordTest(id, ConnectionSyncStatus.FAILED);
            throw new ExternalSystemException("The connection test failed: " + UrlMasking.mask(e.getMessage()));
        }
    }

    private static void requireGitHubToken(ScmType type, String secret) {
        if (type == ScmType.GITHUB && secret == null) {
            throw new InvalidRequestException("A GitHub connection needs a token");
        }
    }

    record Valid(String name, ScmType type, String baseUrl, String username, List<String> includeProjects,
            List<String> excludeRepos, List<String> repositoryUrls, boolean includeOwnRepositories, boolean enabled) {
    }

    /** Package-private so ScmConnectionValidationTest can check the per-type rules without a database. */
    static Valid validate(ScmConnectionUpdate update) {
        if (update == null) {
            throw new InvalidRequestException("A connection document is required");
        }
        String name = required("name", update.name(), NAME_BYTES);
        if (update.type() == null) {
            throw new InvalidRequestException("type is required");
        }
        String baseUrl = required("baseUrl", update.baseUrl(), URL_BYTES);
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        requireScheme(baseUrl);
        String username = update.username() == null || update.username().isBlank() ? null : update.username().strip();
        if (username != null && Utf8.byteLength(username) > USERNAME_BYTES) {
            throw new InvalidRequestException("username is longer than " + USERNAME_BYTES + " bytes");
        }
        if (update.enabled() == null) {
            throw new InvalidRequestException("enabled is required");
        }
        List<String> include = items("includeProjects", update.includeProjects());
        List<String> exclude = items("excludeRepos", update.excludeRepos());
        List<String> urls = urls(update.repositoryUrls());
        boolean own = Boolean.TRUE.equals(update.includeOwnRepositories());
        switch (update.type()) {
            case BITBUCKET_DC -> {
                requireEmpty("repositoryUrls", urls);
                requireNoOwnRepositories(own);
            }
            case GITHUB -> {
                requireEmpty("repositoryUrls", urls);
                if (include.isEmpty() && !own) {
                    throw new InvalidRequestException("includeProjects must name at least one GitHub organization, "
                            + "or includeOwnRepositories must be true");
                }
            }
            case GIT -> {
                requireEmpty("includeProjects", include);
                requireEmpty("excludeRepos", exclude);
                requireNoOwnRepositories(own);
                if (urls.isEmpty()) {
                    throw new InvalidRequestException("repositoryUrls must list at least one clone URL");
                }
                urls = GitRepositoryUrls.check(baseUrl, urls).stream().map(GitRepositoryUrls.Named::url).toList();
            }
        }
        return new Valid(name, update.type(), baseUrl, username, include, exclude, urls, own, update.enabled());
    }

    private static String required(String field, String value, int maxBytes) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
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
                throw new InvalidRequestException("baseUrl must start with http:// or https://");
            }
            if (uri.getUserInfo() != null) {
                throw new InvalidRequestException("baseUrl must not contain credentials; use username and secret");
            }
            if (uri.getHost() == null || uri.isOpaque()) {
                throw new InvalidRequestException("baseUrl must name a host, e.g. https://scm.example.com");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new InvalidRequestException("baseUrl must not contain a query or fragment");
            }
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("baseUrl is not a valid URI");
        }
    }

    private static List<String> items(String field, List<String> values) {
        List<String> items = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank() || value.contains(",")) {
                    throw new InvalidRequestException(field + " items must be non-blank and contain no comma");
                }
                items.add(value.strip());
            }
        }
        if (Utf8.byteLength(String.join(",", items)) > LIST_BYTES) {
            throw new InvalidRequestException(field + " is longer than " + LIST_BYTES + " bytes");
        }
        return List.copyOf(items);
    }

    private static void requireNoOwnRepositories(boolean own) {
        if (own) {
            throw new InvalidRequestException("includeOwnRepositories is only used by GitHub connections");
        }
    }

    private static void requireEmpty(String field, List<String> values) {
        if (!values.isEmpty()) {
            throw new InvalidRequestException(field + " is not used by this connection type");
        }
    }

    /** Clone URLs: stored one per line, so a comma is fine but a line break is not. */
    private static List<String> urls(List<String> values) {
        List<String> urls = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r")) {
                    throw new InvalidRequestException("repositoryUrls items must be non-blank and contain no line break");
                }
                String url = value.strip();
                while (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                urls.add(url);
            }
        }
        // repository_urls is a CLOB, so the list has no byte limit of its own; each URL is capped by GitRepositoryUrls
        return List.copyOf(urls);
    }

    private static String changed(ScmConnectionView before, Valid after, boolean secretChanged) {
        List<String> fields = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            fields.add("name (was " + before.name() + ")");
        }
        if (!before.baseUrl().equals(after.baseUrl())) {
            fields.add("baseUrl");
        }
        if (!Objects.equals(before.username(), after.username())) {
            fields.add("username");
        }
        if (secretChanged) {
            fields.add("secret");
        }
        if (!before.includeProjects().equals(after.includeProjects())) {
            fields.add("includeProjects");
        }
        if (!before.excludeRepos().equals(after.excludeRepos())) {
            fields.add("excludeRepos");
        }
        if (!before.repositoryUrls().equals(after.repositoryUrls())) {
            fields.add("repositoryUrls");
        }
        if (before.includeOwnRepositories() != after.includeOwnRepositories()) {
            fields.add("includeOwnRepositories");
        }
        if (before.enabled() != after.enabled()) {
            fields.add("enabled");
        }
        return fields.isEmpty() ? "nothing" : String.join(", ", fields);
    }
}
