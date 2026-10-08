package com.graphify.repository;

import java.util.List;

public record RepositoryDetail(RepositorySummary repository, List<ModuleView> modules) {
}
