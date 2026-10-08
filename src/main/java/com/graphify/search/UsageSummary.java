package com.graphify.search;

import com.graphify.repository.RepositoryRef;
import java.util.List;

/** Usages of one symbol grouped repository → module → using class (spec §10.3 "usages/summary"). */
public record UsageSummary(long usages, int repositories, List<RepositoryUsage> byRepository) {

    public record RepositoryUsage(RepositoryRef repository, long usages, List<ModuleUsage> modules) {
    }

    public record ModuleUsage(String modulePath, long usages, List<ClassUsage> classes) {
    }

    public record ClassUsage(String classFqn, long usages) {
    }
}
