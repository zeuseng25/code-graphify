package com.graphify.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceLinesTest {

    @Test
    void returnsStrippedLineTruncatedToMaxLength() {
        SourceLines lines = SourceLines.of("a\r\n    money.format(\"10\");   \nlast");

        assertThat(lines.snippet(2, 100)).isEqualTo("money.format(\"10\");");
        assertThat(lines.snippet(2, 5)).isEqualTo("money");
        assertThat(lines.snippet(3, 100)).isEqualTo("last");
    }

    @Test
    void outOfRangeLineGivesEmptySnippet() {
        SourceLines lines = SourceLines.of("only");

        assertThat(lines.snippet(0, 10)).isEmpty();
        assertThat(lines.snippet(2, 10)).isEmpty();
    }

    @Test
    void invalidUtf8BytesAreReplacedNotRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("A.java");
        Files.write(file, new byte[] {'/', '/', ' ', (byte) 0xFE, '\n', 'x'});

        assertThat(SourceLines.read(file).snippet(1, 10)).isEqualTo("// �");
    }

    @Test
    void textReturnsExactRangeAndClampsOutOfRange() {
        SourceLines lines = SourceLines.of("abc\ndef");

        assertThat(lines.text(1, 4)).isEqualTo("bc\nd");
        assertThat(lines.text(5, 100)).isEqualTo("ef");
        assertThat(lines.text(-1, 2)).isEmpty();
        assertThat(lines.text(99, 2)).isEmpty();
        assertThat(lines.text(1, -1)).isEmpty();
    }

    @Test
    void leadingBomIsDroppedSoOffsetsMatchJdt() {
        SourceLines lines = SourceLines.of("\uFEFFa\nb");

        assertThat(lines.text(0, 1)).isEqualTo("a");
        assertThat(lines.snippet(1, 10)).isEqualTo("a");
    }

    @Test
    void truncateLeavesShortTextAlone() {
        assertThat(SourceLines.truncate("@Get(\"/a\")", 100)).isEqualTo("@Get(\"/a\")");
        assertThat(SourceLines.truncate("@Get(\"/a\")", 4)).isEqualTo("@Get");
    }

    @Test
    void truncateNeverSplitsASurrogatePair() {
        String withEmoji = "a😀b";

        assertThat(SourceLines.truncate(withEmoji, 2)).isEqualTo("a");
        assertThat(SourceLines.truncate(withEmoji, 3)).isEqualTo("a😀");
    }
}
