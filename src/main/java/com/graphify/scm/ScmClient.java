package com.graphify.scm;

import java.util.List;

/** Lists the repositories a connection can read. One implementation per {@link ScmType}. */
public interface ScmClient {

    ScmType type();

    List<RemoteRepository> listRepositories(ScmConnection connection);

    /** One cheap authenticated call; throws ScmAuthenticationException when the credentials are rejected. */
    void test(ScmConnection connection);
}
