package com.graphify.maven;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Classpath per module path (only modules Maven produced one for); {@code error} is null when Maven succeeded. */
public record ClasspathResult(Map<String, List<Path>> classpaths, String error) {

    public ClasspathResult {
        classpaths = Map.copyOf(classpaths);
    }
}
