package com.graphify.impact;

import com.graphify.repository.RepositoryRef;

/**
 * A module that uses a different version of a dependency than the one the impact is computed against (spec §10.4).
 * Computed by {@code VersionWarnings}.
 */
public record VersionWarning(
        RepositoryRef repository,
        String modulePath,
        String dependency,
        String usedVersion,
        String declaredVersion) {
}
