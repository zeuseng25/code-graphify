package com.graphify.maven;

import java.time.Instant;

/** A Maven repository as the admin API shows it: never the secret, only whether one is set (spec §6.4). */
public record ArtifactRepositoryView(
        long id,
        String name,
        String url,
        String username,
        boolean secretSet,
        String mirrorOf,
        int sortOrder,
        boolean enabled,
        String lastTestStatus,
        Instant lastTestAt) {
}
