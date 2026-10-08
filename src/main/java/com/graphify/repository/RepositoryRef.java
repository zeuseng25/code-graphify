package com.graphify.repository;

/** Identifies a repository in API responses: a slug is unique only within its project, so the id is the key. */
public record RepositoryRef(long id, String projectKey, String slug) {
}
