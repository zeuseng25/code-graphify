package com.graphify.scm;

import java.util.regex.Pattern;

/** Hides credentials embedded in URLs ({@code https://user:pass@host} → {@code https://***@host}) in any text. */
public final class UrlMasking {

    private static final Pattern USER_INFO = Pattern.compile("(?<=://)[^/@\\s]+@");

    private UrlMasking() {
    }

    public static String mask(String text) {
        return text == null ? null : USER_INFO.matcher(text).replaceAll("***@");
    }
}
