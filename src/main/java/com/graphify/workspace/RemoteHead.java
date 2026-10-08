package com.graphify.workspace;

/** A repository's default branch (short name) and the commit it points to. */
public record RemoteHead(String branch, String commit) {
}
