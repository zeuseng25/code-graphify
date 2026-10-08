package com.graphify.scm;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.workspace.GitAuthenticationException;
import com.graphify.workspace.GitException;
import com.graphify.workspace.GitWorkspace;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** GIT connections: the admin lists the clone URLs; the test reads the first one's HEAD (ls-remote). */
@Component
public class GitConnectionClient implements ScmClient {

    private static final Set<String> NETWORK_SCHEMES = Set.of("http", "https");

    private final GitWorkspace workspace;

    /** Schemes a base URL may have. Production keeps http and https; a test that clones local repositories widens it. */
    volatile Set<String> allowedSchemes = NETWORK_SCHEMES;

    public GitConnectionClient(GitWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public ScmType type() {
        return ScmType.GIT;
    }

    /** A stored base URL is only trusted to be http(s); anything else (file:, ssh:) would reach local resources. */
    private void requireNetworkBase(ScmConnection connection) {
        String scheme;
        try {
            scheme = new URI(connection.baseUrl()).getScheme();
        } catch (java.net.URISyntaxException e) {
            scheme = null;
        }
        if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new ScmException("Connection '" + connection.name() + "' has a base URL that is not http or https");
        }
    }

    @Override
    public List<RemoteRepository> listRepositories(ScmConnection connection) {
        requireNetworkBase(connection);
        if (connection.repositoryUrls().isEmpty()) {
            // cannot happen after validation; an empty list would make a sync deactivate every repository
            throw new ScmException("Connection '" + connection.name() + "' lists no repository URL");
        }
        try {
            // re-checked against the current baseUrl: a stored URL that no longer fits fails the whole listing
            return connection.repositoryUrls().stream().map(url -> GitRepositoryUrls.name(connection.baseUrl(), url))
                    .map(named -> new RemoteRepository(named.projectKey(), named.slug(), named.slug(), named.url()))
                    .toList();
        } catch (InvalidRequestException e) {
            throw new ScmException("Connection '" + connection.name() + "' has a stored repository URL that is no "
                    + "longer valid: " + e.getMessage());
        }
    }

    @Override
    public void test(ScmConnection connection) {
        requireNetworkBase(connection);
        if (connection.repositoryUrls().isEmpty()) {
            throw new ScmException("Connection '" + connection.name() + "' lists no repository URL");
        }
        String first = connection.repositoryUrls().getFirst();
        try {
            // same rule as listing: credentials only go to URLs under the connection's own base URL
            GitRepositoryUrls.name(connection.baseUrl(), first);
            workspace.remoteHead(first, connection.gitAuthorization());
        } catch (InvalidRequestException e) {
            throw new ScmException("Connection '" + connection.name() + "' has a repository URL that is not valid: "
                    + e.getMessage());
        } catch (GitAuthenticationException e) {
            throw new ScmAuthenticationException("The Git server rejected the credentials of connection '"
                    + connection.name() + "'");
        } catch (GitException e) {
            throw new ScmException("Connection '" + connection.name() + "' could not read " + UrlMasking.mask(first)
                    + ": " + e.getMessage());
        }
    }
}
