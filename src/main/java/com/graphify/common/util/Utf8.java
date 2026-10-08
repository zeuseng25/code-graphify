package com.graphify.common.util;

import java.nio.charset.StandardCharsets;

/** UTF-8 byte arithmetic for {@code VARCHAR2(n BYTE)} columns. */
public final class Utf8 {

    private Utf8() {
    }

    public static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Longest prefix of {@code text} whose UTF-8 encoding fits in {@code maxBytes}; never splits a code point. */
    public static String truncateToBytes(String text, int maxBytes) {
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int length = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + length > maxBytes) {
                return text.substring(0, i);
            }
            bytes += length;
            i += Character.charCount(codePoint);
        }
        return text;
    }
}
