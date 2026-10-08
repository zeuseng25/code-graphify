package com.graphify.maven;

import com.graphify.scm.UrlMasking;

/** A Maven repository from artifact_repository with its secret decrypted; toString never shows the secret. */
public record ArtifactRepository(long id, String name, String url, String username, String secret, String mirrorOf) {

    @Override
    public String toString() {
        return "ArtifactRepository[id=" + id + ", name=" + name + ", url=" + UrlMasking.mask(url) + ", mirrorOf="
                + mirrorOf + "]";
    }
}
