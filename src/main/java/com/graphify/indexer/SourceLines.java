package com.graphify.indexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Source text split into lines. A leading BOM is dropped because JDT does not count it in node offsets. Undecodable bytes become U+FFFD instead of failing the file. */
final class SourceLines {

    private final String content;
    private final List<String> lines;

    private SourceLines(String content) {
        this.content = content.startsWith("\uFEFF") ? content.substring(1) : content;
        this.lines = this.content.lines().toList();
    }

    static SourceLines read(Path file) throws IOException {
        return of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    static SourceLines of(String content) {
        return new SourceLines(content);
    }

    String snippet(int line, int maxLength) {
        if (line < 1 || line > lines.size()) {
            return "";
        }
        return truncate(lines.get(line - 1).strip(), maxLength);
    }

    /** The exact source range, clamped to the content; empty when the start or length is out of range. */
    String text(int start, int length) {
        if (start < 0 || length < 0 || start > content.length()) {
            return "";
        }
        return content.substring(start, Math.min(content.length(), start + length));
    }

    static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end);
    }
}
