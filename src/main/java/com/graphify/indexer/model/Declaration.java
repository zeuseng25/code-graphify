package com.graphify.indexer.model;

/** Where a SOURCE symbol is declared. {@code line} is the line of the declared name. */
public record Declaration(String symbolKey, String modulePath, String filePath, int line) {
}
