package com.graphify.impact;

import com.graphify.repository.RepositoryRef;
import java.time.Instant;
import java.util.Comparator;

/** Where a module lives and how fresh and complete its index is (spec §5.6); {@code repository} is the slug. */
public record RepoState(
        long moduleId,
        long repositoryId,
        String projectKey,
        String repository,
        String modulePath,
        String classpathMode,
        String lastIndexedCommit,
        Instant lastIndexedAt) {

    /** Stable repository order: project key, slug, then id. */
    static final Comparator<RepositoryRef> REPOSITORY_ORDER = Comparator.comparing(RepositoryRef::projectKey)
            .thenComparing(RepositoryRef::slug).thenComparingLong(RepositoryRef::id);

    RepositoryRef repositoryRef() {
        return new RepositoryRef(repositoryId, projectKey, repository);
    }
}
