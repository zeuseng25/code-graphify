package com.graphify.impact;

import com.graphify.repository.RepositoryRef;

/**
 * An affected endpoint, job or listener. {@code http} is true for a request mapping (a {@code *Mapping} annotation);
 * {@code httpMethod}/{@code httpPath} are set only then, and only when resolvable.
 */
public record EntryPoint(
        long symbolId,
        String key,
        String display,
        RepositoryRef repository,
        String modulePath,
        String label,
        String annotation,
        boolean http,
        String httpMethod,
        String httpPath) {
}
