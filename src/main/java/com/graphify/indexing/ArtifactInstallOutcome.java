package com.graphify.indexing;

/** A provider's install result; {@code error} is masked Maven output or a note, null when there is nothing to say. */
public record ArtifactInstallOutcome(ArtifactInstallStatus status, String error) {
}
