package com.graphify.store;

/**
 * What was written. {@code skippedSymbols}: symbols too long for their columns; {@code skippedRows}: declarations
 * and usages dropped because they reference a skipped symbol or their file path is too long.
 */
public record WriteSummary(int symbols, int declarations, int usages, int skippedSymbols, int skippedRows) {
}
