package com.graphify.maven;

import java.util.List;

/** A checkout's modules; {@code roots} are the checkout-relative folders whose reactors build them ("." = the root). */
public record MavenProject(List<MavenModule> modules, List<String> warnings, List<String> roots) {

    public MavenProject {
        modules = List.copyOf(modules);
        warnings = List.copyOf(warnings);
        roots = List.copyOf(roots);
    }

    public MavenProject(List<MavenModule> modules, List<String> warnings) {
        this(modules, warnings, modules.stream().anyMatch(MavenModule::hasPom) ? List.of(".") : List.of());
    }
}
