package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.common.exception.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Per-type rules; reaches the package-private static validate directly, without a database. */
class ScmConnectionValidationTest {

    private static final String BASE = "https://git.corp/scm";

    private static ScmConnectionUpdate update(ScmType type, List<String> include, List<String> exclude,
            List<String> urls) {
        return update(type, include, exclude, urls, null);
    }

    private static ScmConnectionUpdate update(ScmType type, List<String> include, List<String> exclude,
            List<String> urls, Boolean own) {
        return new ScmConnectionUpdate("c", type, BASE, null, null, include, exclude, urls, own, true);
    }

    @Test
    void aGitHubConnectionMayListOnlyTheTokenOwnersRepositories() {
        ScmConnectionAdministration.Valid valid = ScmConnectionAdministration.validate(
                update(ScmType.GITHUB, List.of(), List.of("zeuseng25/old-*"), List.of(), true));

        assertThat(valid.includeProjects()).isEmpty();
        assertThat(valid.includeOwnRepositories()).isTrue();
    }

    @Test
    void aGitHubConnectionNeedsAnOrganizationOrItsOwnRepositories() {
        rejects(update(ScmType.GITHUB, List.of(), List.of(), List.of(), false), "at least one GitHub organization");
        rejects(update(ScmType.GITHUB, List.of(), List.of(), List.of(), null), "includeOwnRepositories");
    }

    @Test
    void onlyGitHubListsTheTokenOwnersRepositories() {
        rejects(update(ScmType.BITBUCKET_DC, List.of(), List.of(), List.of(), true), "includeOwnRepositories");
        rejects(update(ScmType.GIT, List.of(), List.of(), List.of(BASE + "/a/b.git"), true),
                "includeOwnRepositories");
        assertThat(ScmConnectionAdministration.validate(update(ScmType.GITHUB, List.of("acme"), List.of(), List.of(),
                null)).includeOwnRepositories()).isFalse();
    }

    private static void rejects(ScmConnectionUpdate update, String message) {
        assertThatThrownBy(() -> ScmConnectionAdministration.validate(update))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining(message);
    }

    @Test
    void aGitHubConnectionNeedsAnOrganization() {
        rejects(update(ScmType.GITHUB, List.of(), List.of(), List.of()), "includeProjects");
    }

    @Test
    void aGitHubConnectionHasNoRepositoryUrls() {
        rejects(update(ScmType.GITHUB, List.of("acme"), List.of(), List.of(BASE + "/a/b.git")), "repositoryUrls");
    }

    @Test
    void aBitbucketConnectionHasNoRepositoryUrls() {
        rejects(update(ScmType.BITBUCKET_DC, List.of(), List.of(), List.of(BASE + "/a/b.git")), "repositoryUrls");
    }

    @Test
    void aGitConnectionHasNoFilters() {
        rejects(update(ScmType.GIT, List.of("acme"), List.of(), List.of(BASE + "/a/b.git")), "includeProjects");
        rejects(update(ScmType.GIT, List.of(), List.of("x"), List.of(BASE + "/a/b.git")), "excludeRepos");
    }

    @Test
    void aGitConnectionNeedsUrlsOnItsBase() {
        rejects(update(ScmType.GIT, List.of(), List.of(), List.of()), "repositoryUrls");
        rejects(update(ScmType.GIT, List.of(), List.of(), List.of("https://other/scm/a/b.git")), "other");
    }

    @Test
    void aValidDocumentOfEachTypePasses() {
        assertThat(ScmConnectionAdministration.validate(update(ScmType.BITBUCKET_DC, List.of("P"), List.of("P/x"),
                List.of())).type()).isEqualTo(ScmType.BITBUCKET_DC);
        assertThat(ScmConnectionAdministration.validate(update(ScmType.GITHUB, List.of("acme"), List.of("acme/old"),
                null)).type()).isEqualTo(ScmType.GITHUB);
        assertThat(ScmConnectionAdministration.validate(update(ScmType.GIT, null, null,
                List.of(" " + BASE + "/a/b.git/ ", BASE + "/c.git"))).repositoryUrls())
                .containsExactly(BASE + "/a/b.git", BASE + "/c.git");
    }
}
