package com.graphify.maven;

/** A Maven coordinate; {@link #of} refuses partial or unresolved ones, which never match anything. */
public record Gav(String groupId, String artifactId, String version) {

    public static Gav of(String groupId, String artifactId, String version) {
        if (resolved(groupId) && resolved(artifactId) && resolved(version)) {
            return new Gav(groupId.strip(), artifactId.strip(), version.strip());
        }
        return null;
    }

    private static boolean resolved(String part) {
        return part != null && !part.isBlank() && !part.contains("${");
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
