package com.graphify.scm;

import java.util.regex.Pattern;

/** Applies a connection's include_projects (project keys) and exclude_repos ({@code PROJECT/slug} globs); {@link #excluded} applies only the excludes. */
final class RepositoryFilter {

    private RepositoryFilter() {
    }

    static boolean accepts(ScmConnection connection, RemoteRepository repository) {
        if (!connection.includeProjects().isEmpty() && connection.includeProjects().stream()
                .noneMatch(project -> project.equalsIgnoreCase(repository.projectKey()))) {
            return false;
        }
        return !excluded(connection, repository);
    }

    /** Whether an exclude_repos glob matches {@code PROJECT/slug}; the include list is not consulted. */
    static boolean excluded(ScmConnection connection, RemoteRepository repository) {
        String fullName = repository.projectKey() + "/" + repository.slug();
        return connection.excludeRepos().stream().anyMatch(pattern -> glob(pattern).matcher(fullName).matches());
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
