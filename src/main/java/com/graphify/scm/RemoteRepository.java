package com.graphify.scm;

/** A repository as the SCM lists it; {@code cloneUrl} is the HTTP(S) clone link. */
public record RemoteRepository(String projectKey, String slug, String name, String cloneUrl) {
}
