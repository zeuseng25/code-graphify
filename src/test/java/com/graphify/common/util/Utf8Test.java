package com.graphify.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Utf8Test {

    @Test
    void countsEncodedBytesNotChars() {
        assertThat(Utf8.byteLength("abc")).isEqualTo(3);
        assertThat(Utf8.byteLength("şğü")).isEqualTo(6);
        assertThat(Utf8.byteLength("😀")).isEqualTo(4);
    }

    @Test
    void truncatesToWholeCodePointsWithinTheByteLimit() {
        assertThat(Utf8.truncateToBytes("abc", 10)).isEqualTo("abc");
        assertThat(Utf8.truncateToBytes("aşb", 2)).isEqualTo("a");
        assertThat(Utf8.truncateToBytes("aşb", 3)).isEqualTo("aş");
        assertThat(Utf8.truncateToBytes("a😀", 4)).isEqualTo("a");
        assertThat(Utf8.truncateToBytes("a😀", 5)).isEqualTo("a😀");
        assertThat(Utf8.byteLength(Utf8.truncateToBytes("ş".repeat(3000), 4000))).isLessThanOrEqualTo(4000);
    }
}
