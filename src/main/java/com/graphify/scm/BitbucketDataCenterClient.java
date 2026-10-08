package com.graphify.scm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Lists repositories through Bitbucket Data Center's REST API ({@code /rest/api/1.0/repos}, paged). */
@Component
public class BitbucketDataCenterClient implements ScmClient {

    /** Bitbucket DC REST resource listing every repository the caller can read. */
    private static final String REPOS_PATH = "/rest/api/1.0/repos?start={start}&limit={limit}";
    /** Page size of the connection test: one repository is enough to prove the URL and credentials work. */
    private static final int TEST_PAGE_SIZE = 1;
    /** Name Bitbucket gives the HTTP(S) entry among a repository's clone links. */
    private static final String HTTP_CLONE_LINK = "http";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Link(String href, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Links(@JsonProperty("clone") List<Link> cloneLinks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Project(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repo(String slug, String name, Project project, Links links, Boolean archived) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(List<Repo> values, boolean isLastPage, Integer nextPageStart) {
    }

    private final AppSettings settings;

    public BitbucketDataCenterClient(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_DC;
    }

    @Override
    public List<RemoteRepository> listRepositories(ScmConnection connection) {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(settings.getDuration(SettingKeys.SCM_CONNECT_TIMEOUT)).build()) {
            RestClient client = client(connection, http);
            int pageSize = settings.getInt(SettingKeys.SCM_PAGE_SIZE);
            int retries = settings.getInt(SettingKeys.SCM_RETRY_COUNT);
            List<RemoteRepository> repositories = new ArrayList<>();
            int start = 0;
            while (true) {
                int pageStart = start;
                Page page = withRetry(connection, () -> client.get().uri(REPOS_PATH, pageStart, pageSize).retrieve()
                        .body(Page.class), retries);
                if (page == null || page.values() == null) {
                    // a listing we cannot read must fail the sync: an empty result would deactivate every repository
                    throw new ScmException("Bitbucket returned no repository page for connection '" + connection.name()
                            + "' (start " + pageStart + ")");
                }
                for (Repo repo : page.values()) {
                    String cloneUrl = httpCloneUrl(repo);
                    if (Boolean.TRUE.equals(repo.archived()) || cloneUrl == null || repo.project() == null) {
                        continue;
                    }
                    RemoteRepository remote = new RemoteRepository(repo.project().key(), repo.slug(), repo.name(), cloneUrl);
                    if (RepositoryFilter.accepts(connection, remote)) {
                        repositories.add(remote);
                    }
                }
                if (page.isLastPage()) {
                    break;
                }
                if (page.nextPageStart() == null || page.nextPageStart() <= pageStart) {
                    throw new ScmException("Bitbucket returned a page without a usable nextPageStart for connection '"
                            + connection.name() + "' (start " + pageStart + ", next " + page.nextPageStart() + ")");
                }
                start = page.nextPageStart();
            }
            return repositories;
        }
    }

    @Override
    public void test(ScmConnection connection) {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(settings.getDuration(SettingKeys.SCM_CONNECT_TIMEOUT)).build()) {
            RestClient client = client(connection, http);
            withRetry(connection, () -> client.get().uri(REPOS_PATH, 0, TEST_PAGE_SIZE).retrieve().body(Page.class), 0);
        }
    }

    private RestClient client(ScmConnection connection, HttpClient http) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(settings.getDuration(SettingKeys.SCM_READ_TIMEOUT));
        RestClient.Builder builder = RestClient.builder().requestFactory(factory).baseUrl(connection.baseUrl());
        AuthorizationHeader.of(connection.username(), connection.secret())
                .ifPresent(header -> builder.defaultHeader("Authorization", header));
        return builder.build();
    }

    private <T> T withRetry(ScmConnection connection, Supplier<T> call, int retries) {
        Duration backoff = settings.getDuration(SettingKeys.SCM_RETRY_BACKOFF);
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException e) {
                HttpStatusCode status = e.getStatusCode();
                if (status.value() == 401 || status.value() == 403) {
                    throw new ScmAuthenticationException("Bitbucket rejected the credentials of connection '"
                            + connection.name() + "' (HTTP " + status.value() + ")");
                }
                throw new ScmException("Bitbucket request for connection '" + connection.name() + "' failed with HTTP "
                        + status.value());
            } catch (HttpServerErrorException | ResourceAccessException e) {
                if (attempt >= retries) {
                    throw new ScmException("Bitbucket request for connection '" + connection.name() + "' failed after "
                            + (attempt + 1) + " attempts: " + UrlMasking.mask(e.getMessage()));
                }
                sleep(backoff.multipliedBy(1L << Math.min(attempt, 20)));
            } catch (RestClientException e) {
                // unreadable bodies, unknown content types: never retried, never an empty listing
                throw new ScmException("Bitbucket response for connection '" + connection.name()
                        + "' could not be read: " + UrlMasking.mask(e.getMessage()));
            }
        }
    }

    private static String httpCloneUrl(Repo repo) {
        if (repo.links() == null || repo.links().cloneLinks() == null) {
            return null;
        }
        return repo.links().cloneLinks().stream().filter(link -> HTTP_CLONE_LINK.equals(link.name()))
                .map(Link::href).findFirst().orElse(null);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScmException("Interrupted while waiting to retry Bitbucket");
        }
    }
}
