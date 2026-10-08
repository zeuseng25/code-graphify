package com.graphify.search;

import com.graphify.repository.RepositoryRef;

public record DeclarationView(RepositoryRef repository, String modulePath, String filePath, int line) {
}
