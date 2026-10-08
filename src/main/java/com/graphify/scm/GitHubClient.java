package com.graphify.scm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.HttpHeaders;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Lists the repositories of GitHub organizations ({@code GET /orgs/{org}/repos}) and, when asked, the token owner's
 * own ({@code GET /user/repos}), paged by the Link header.
 */
@Component
public class GitHubClient implements ScmClient {

    /** GitHub REST resource listing an organization's repositories. */
    private static final String ORG_REPOS_PATH = "/orgs/{org}/repos?per_page={perPage}&page=1";
    /** GitHub REST resource listing the repositories the token's owner owns, private ones included. */
    private static final String OWN_REPOS_PATH = "/user/repos?affiliation=owner&visibility=all&per_page={perPage}&page=1";
    /** GitHub REST resource naming the token's owner: the own-only connection test. */
    private static final String USER_PATH = "/user";
    /** Subject of error messages about the own-repository listing. */
    private static final String OWN_SUBJECT = "user of this token";
    /** Media type and API version GitHub documents for its REST API. */
    private static final String MEDIA_TYPE = "application/vnd.github+json";
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";
    /** Page size of the connection test: one repository is enough to prove the URL and token work. */
    private static final int TEST_PAGE_SIZE = 1;
    /** One entry of a Link header: {@code <url>; rel="next"}. */
    private static final Pattern LINK_ENTRY = Pattern.compile("^\\s*<([^>]*)>\\s*;(.*)$");
    private static final Pattern REL_NEXT = Pattern.compile("(?i);?\\s*rel\\s*=\\s*\"?next\"?\\s*(;|$)");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Owner(String login) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repo(String name, Owner owner, @JsonProperty("clone_url") String cloneUrl, Boolean archived, Boolean disabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(String login) {
    }

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final AppSettings settings;

    public GitHubClient(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public ScmType type() {
        return ScmType.GITHUB;
    }

    @Override
    public List<RemoteRepository> listRepositories(ScmConnection connection) {
        requireSource(connection);
        try (HttpClient http = httpClient()) {
            RestClient client = client(connection, http);
            int pageSize = settings.getInt(SettingKeys.SCM_PAGE_SIZE);
            int retries = settings.getInt(SettingKeys.SCM_RETRY_COUNT);
            URI base = baseUri(connection);
            Map<String, RemoteRepository> repositories = new LinkedHashMap<>();
            for (String org : connection.includeProjects()) {
                listPages(connection, client, base, orgPage(connection, org, pageSize), organization(org), retries,
                        remote -> RepositoryFilter.accepts(connection, remote), repositories);
            }
            if (connection.includeOwnRepositories()) {
                // the owner's login is not an organization in includeProjects: only the excludes apply
                listPages(connection, client, base, ownPage(connection, pageSize), OWN_SUBJECT, retries,
                        remote -> !RepositoryFilter.excluded(connection, remote), repositories);
            }
            return List.copyOf(repositories.values());
        }
    }

    @Override
    public void test(ScmConnection connection) {
        requireSource(connection);
        try (HttpClient http = httpClient()) {
            RestClient client = client(connection, http);
            if (!connection.includeProjects().isEmpty()) {
                String org = connection.includeProjects().getFirst();
                URI first = orgPage(connection, org, TEST_PAGE_SIZE);
                withRetry(connection, organization(org), () -> fetch(client, first, connection), 0);
                return;
            }
            URI user = UriComponentsBuilder.fromUriString(connection.baseUrl() + USER_PATH).build().encode().toUri();
            withRetry(connection, OWN_SUBJECT, () -> fetchUser(client, user, connection), 0);
        }
    }

    private static void requireSource(ScmConnection connection) {
        if (connection.includeProjects().isEmpty() && !connection.includeOwnRepositories()) {
            throw new ScmException("GitHub connection '" + connection.name()
                    + "' names no organization and does not include its own repositories");
        }
    }

    private static final String ORGANIZATION = "organization ";

    private static String organization(String org) {
        return ORGANIZATION + "'" + org + "'";
    }

    private void listPages(ScmConnection connection, RestClient client, URI base, URI first, String subject,
            int retries, Predicate<RemoteRepository> filter, Map<String, RemoteRepository> out) {
        Set<URI> fetched = new HashSet<>();
        fetched.add(first);
        URI current = first;
        int pages = 0;
        while (true) {
            URI target = current;
            ResponseEntity<List<Repo>> response = withRetry(connection, subject,
                    () -> fetch(client, target, connection), retries);
            if (response.getBody() == null) {
                // a listing we cannot read must fail the sync: an empty result would deactivate every repository
                throw new ScmException("GitHub returned no repository page for " + subject
                        + " of connection '" + connection.name() + "' (page " + (pages + 1) + ")");
            }
            pages++;
            for (Repo repo : response.getBody()) {
                if (Boolean.TRUE.equals(repo.archived()) || Boolean.TRUE.equals(repo.disabled())
                        || repo.cloneUrl() == null || repo.owner() == null || repo.owner().login() == null
                        || repo.name() == null) {
                    continue;
                }
                if (!acceptableCloneUrl(base, repo.cloneUrl())) {
                    log.warn("Skipping GitHub repository {}/{} of connection '{}': unacceptable clone_url {}",
                            repo.owner().login(), repo.name(), connection.name(), UrlMasking.mask(repo.cloneUrl()));
                    continue;
                }
                RemoteRepository remote = new RemoteRepository(repo.owner().login(), repo.name(), repo.name(),
                        repo.cloneUrl());
                if (filter.test(remote)) {
                    out.putIfAbsent((remote.projectKey() + "/" + remote.slug()).toLowerCase(Locale.ROOT), remote);
                }
            }
            String link = response.getHeaders().getFirst("Link");
            Optional<String> nextRef = nextLink(link);
            if (nextRef.isEmpty()) {
                return;
            }
            URI uri = checkedNext(connection, base, nextRef.get(), fetched);
            fetched.add(uri);
            current = uri;
        }
    }

    private static URI orgPage(ScmConnection connection, String org, int perPage) {
        return UriComponentsBuilder.fromUriString(connection.baseUrl() + ORG_REPOS_PATH).buildAndExpand(org, perPage)
                .encode().toUri();
    }

    private static URI ownPage(ScmConnection connection, int perPage) {
        return UriComponentsBuilder.fromUriString(connection.baseUrl() + OWN_REPOS_PATH).buildAndExpand(perPage)
                .encode().toUri();
    }

    private static ResponseEntity<User> fetchUser(RestClient client, URI uri, ScmConnection connection) {
        ResponseEntity<User> response = client.get().uri(uri).retrieve().toEntity(User.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ScmException("GitHub answered connection '" + connection.name() + "' with HTTP "
                    + response.getStatusCode().value() + " instead of the token's user");
        }
        return response;
    }

    /** Absolute URIs bypass the client's baseUrl; callers only pass URIs of the configured origin. */
    private static ResponseEntity<List<Repo>> fetch(RestClient client, URI uri, ScmConnection connection) {
        ResponseEntity<List<Repo>> response = client.get().uri(uri).retrieve()
                .toEntity(new ParameterizedTypeReference<List<Repo>>() {
                });
        if (!response.getStatusCode().is2xxSuccessful()) {
            // redirects are never followed (they could carry the token elsewhere); say so instead of reading a body
            throw new ScmException("GitHub answered connection '" + connection.name() + "' with HTTP "
                    + response.getStatusCode().value() + " instead of a repository page");
        }
        return response;
    }

    /** https always; http only when the base URL itself is http. A host is required and credentials are refused. */
    private static boolean acceptableCloneUrl(URI base, String cloneUrl) {
        try {
            URI uri = new URI(cloneUrl);
            String scheme = uri.getScheme();
            boolean schemeOk = "https".equalsIgnoreCase(scheme)
                    || ("http".equalsIgnoreCase(scheme) && "http".equalsIgnoreCase(base.getScheme()));
            return schemeOk && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The token may only travel to the configured origin: a next link anywhere else (or with userinfo) fails. */
    private static URI checkedNext(ScmConnection connection, URI base, String ref, Set<URI> fetched) {
        URI uri;
        try {
            uri = new URI(ref);
        } catch (URISyntaxException e) {
            throw new ScmException("GitHub returned an invalid next page link for connection '" + connection.name()
                    + "'");
        }
        if (uri.getRawUserInfo() != null || !UrlOrigins.sameOrigin(uri, base)) {
            throw new ScmException("GitHub returned a next page link outside the base URL of connection '"
                    + connection.name() + "': " + UrlMasking.mask(ref));
        }
        if (fetched.contains(uri)) {
            throw new ScmException("GitHub returned a next page link already fetched for connection '"
                    + connection.name() + "': " + UrlMasking.mask(ref));
        }
        return uri;
    }

    /** The URL of the {@code rel="next"} entry of a Link header, if any. */
    static Optional<String> nextLink(String header) {
        if (header == null) {
            return Optional.empty();
        }
        for (String entry : header.split(",")) {
            Matcher m = LINK_ENTRY.matcher(entry);
            if (m.matches() && REL_NEXT.matcher(";" + m.group(2)).find()) {
                return Optional.of(m.group(1).strip());
            }
        }
        return Optional.empty();
    }

    private static URI baseUri(ScmConnection connection) {
        try {
            return new URI(connection.baseUrl());
        } catch (URISyntaxException e) {
            throw new ScmException("The base URL of connection '" + connection.name() + "' is not a valid URI");
        }
    }

    private HttpClient httpClient() {
        // the JDK client never follows redirects by default; keep it so the token cannot follow one to another origin
        return HttpClient.newBuilder().connectTimeout(settings.getDuration(SettingKeys.SCM_CONNECT_TIMEOUT))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private RestClient client(ScmConnection connection, HttpClient http) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(settings.getDuration(SettingKeys.SCM_READ_TIMEOUT));
        RestClient.Builder builder = RestClient.builder().requestFactory(factory).baseUrl(connection.baseUrl())
                .defaultHeader("Accept", MEDIA_TYPE).defaultHeader(API_VERSION_HEADER, API_VERSION);
        if (connection.secret() != null) {
            // GitHub REST takes the token as Bearer whatever the username says
            builder.defaultHeader("Authorization", "Bearer " + connection.secret());
        }
        return builder.build();
    }

    private <T> T withRetry(ScmConnection connection, String subject, Supplier<T> call, int retries) {
        Duration backoff = settings.getDuration(SettingKeys.SCM_RETRY_BACKOFF);
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException e) {
                HttpStatusCode status = e.getStatusCode();
                if (isRateLimited(e)) {
                    throw new ScmException("GitHub rate limit reached for connection '" + connection.name()
                            + "' (HTTP " + status.value() + ")" + resetHint(e.getResponseHeaders()));
                }
                if (status.value() == 401 || status.value() == 403) {
                    throw new ScmAuthenticationException("GitHub rejected the credentials of connection '"
                            + connection.name() + "' (HTTP " + status.value() + ")");
                }
                if (status.value() == 404) {
                    // the usual cause is a personal account typed as an organization (/orgs/{login} is 404)
                    String hint = subject.startsWith(ORGANIZATION) ? "; a personal account is not an organization,"
                            + " use includeOwnRepositories for the token owner's own repositories" : "";
                    throw new ScmException("GitHub " + subject + " was not found or is not visible to connection '"
                            + connection.name() + "'" + hint);
                }
                throw new ScmException("GitHub request for connection '" + connection.name() + "' failed with HTTP "
                        + status.value());
            } catch (HttpServerErrorException | ResourceAccessException e) {
                if (attempt >= retries) {
                    throw new ScmException("GitHub request for connection '" + connection.name() + "' failed after "
                            + (attempt + 1) + " attempts: " + UrlMasking.mask(e.getMessage()));
                }
                sleep(backoff.multipliedBy(1L << Math.min(attempt, 20)));
            } catch (RestClientException e) {
                // unreadable bodies, unknown content types: never retried, never an empty listing
                throw new ScmException("GitHub response for connection '" + connection.name()
                        + "' could not be read: " + UrlMasking.mask(e.getMessage()));
            }
        }
    }

    private static boolean isRateLimited(HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        HttpHeaders headers = e.getResponseHeaders();
        if (status == 429) {
            return true;
        }
        return status == 403 && headers != null
                && ("0".equals(headers.getFirst("X-RateLimit-Remaining")) || headers.getFirst("Retry-After") != null);
    }

    private static String resetHint(HttpHeaders headers) {
        if (headers == null) {
            return "";
        }
        String retryAfter = headers.getFirst("Retry-After");
        if (retryAfter != null) {
            return ", retry after " + UrlMasking.mask(retryAfter) + " seconds";
        }
        String reset = headers.getFirst("X-RateLimit-Reset");
        return reset == null ? "" : ", resets at epoch second " + UrlMasking.mask(reset);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScmException("Interrupted while waiting to retry GitHub");
        }
    }
}
