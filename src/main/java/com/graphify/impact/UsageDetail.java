package com.graphify.impact;

/** Where a usage is written; read only for the edges an impact result keeps. */
public record UsageDetail(String filePath, int line, int column, String snippet) {
}
