package com.graphify.indexing;

import com.graphify.maven.MavenProject;
import java.nio.file.Path;

/**
 * A repository checked out and read in a run's first phase. {@code unchanged} is today's skip rule (same commit, last
 * run not partial, not forced); the index phase applies it unless a provider of this repository was just installed.
 * {@code prepareMillis} is the time of the head check, checkout and pom reading; it is added to the repository's
 * recorded duration, which therefore covers preparing and indexing but not the install phase.
 */
public record PreparedRepository(long repositoryId, String slug, String commit, Path checkout, MavenProject project,
        boolean unchanged, String lastInstalledCommit, long prepareMillis) {
}
