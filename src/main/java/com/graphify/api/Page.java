package com.graphify.api;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long total) {

    public Page {
        items = List.copyOf(items);
    }
}
