package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepositoryFilterTest {

    private static ScmConnection connection(List<String> include, List<String> exclude) {
        return new ScmConnection(1, "c", ScmType.BITBUCKET_DC, "http://x", null, "t", include, exclude);
    }

    private static RemoteRepository repo(String project, String slug) {
        return new RemoteRepository(project, slug, slug, "http://x/" + slug + ".git");
    }

    @Test
    void includeProjectsAndExcludeGlobsApplyCaseInsensitively() {
        ScmConnection filtered = connection(List.of("shop", "PAY"), List.of("SHOP/*-archive", "pay/legacy?"));

        assertThat(RepositoryFilter.accepts(filtered, repo("SHOP", "api"))).isTrue();
        assertThat(RepositoryFilter.accepts(filtered, repo("SHOP", "old-archive"))).isFalse();
        assertThat(RepositoryFilter.accepts(filtered, repo("PAY", "legacy1"))).isFalse();
        assertThat(RepositoryFilter.accepts(filtered, repo("PAY", "legacy12"))).isTrue();
        assertThat(RepositoryFilter.accepts(filtered, repo("HR", "api"))).isFalse();
        assertThat(RepositoryFilter.accepts(connection(List.of(), List.of()), repo("HR", "api"))).isTrue();
    }

    @Test
    void connectionToStringNeverShowsTheSecret() {
        assertThat(connection(List.of(), List.of()).toString()).doesNotContain("t,").doesNotContain("secret");
        assertThat(new ScmConnection(1, "c", ScmType.BITBUCKET_DC, "http://x", "u", "TOPSECRET", List.of(), List.of())
                .toString()).doesNotContain("TOPSECRET");
        assertThat(new ScmConnection(1, "c", ScmType.BITBUCKET_DC, "https://bob:hunter2@scm", "u", "s", List.of(),
                List.of()).toString()).doesNotContain("hunter2").contains("https://***@scm");
    }

    @Test
    void excludedIgnoresTheIncludeList() {
        ScmConnection filtered = connection(List.of("shop"), List.of("zeuseng25/*-old"));

        assertThat(RepositoryFilter.excluded(filtered, repo("zeuseng25", "zeus-fw"))).isFalse();
        assertThat(RepositoryFilter.excluded(filtered, repo("zeuseng25", "backend-old"))).isTrue();
        assertThat(RepositoryFilter.accepts(filtered, repo("zeuseng25", "zeus-fw"))).isFalse();
    }
}
