package com.graphify.scm;

public enum ScmType {
    /** Bitbucket Data Center / Server, listed through its REST API. */
    BITBUCKET_DC,
    /** GitHub (github.com or Enterprise Server), organizations listed through the REST API with a token. */
    GITHUB,
    /** Plain Git servers: the admin lists the clone URLs explicitly. */
    GIT
}
