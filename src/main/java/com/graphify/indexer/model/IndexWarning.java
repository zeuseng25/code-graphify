package com.graphify.indexer.model;

/** Something the indexer skipped. {@code line} is 0 when the whole file is affected. */
public record IndexWarning(String modulePath, String filePath, int line, String message) {
}
