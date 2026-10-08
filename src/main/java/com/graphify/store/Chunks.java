package com.graphify.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Chunks {

    /** Oracle rejects IN lists longer than 1000 items (ORA-01795). */
    public static final int MAX_IN_LIST = 1000;

    private Chunks() {
    }

    /** {@code "?,?,?"} for {@code count} bind parameters. */
    public static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public static <T> List<List<T>> of(List<T> items, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("chunk size must be >= 1 but was " + size);
        }
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < items.size(); from += size) {
            chunks.add(items.subList(from, Math.min(items.size(), from + size)));
        }
        return chunks;
    }
}
