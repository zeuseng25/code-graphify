package com.graphify.maven;

import com.graphify.scm.UrlMasking;

/** A full create/PUT document; {@code secret} null keeps the stored one for the same url and username, "" clears it. */
public record ArtifactRepositoryUpdate(
        String name,
        String url,
        String username,
        String secret,
        String mirrorOf,
        Integer sortOrder,
        Boolean enabled) {

    @Override
    public String toString() {
        return "ArtifactRepositoryUpdate[name=" + name + ", url=" + UrlMasking.mask(url) + "]";
    }
}
