package com.graphify.api;

public record Paging(int page, int size) {

    public long offset() {
        return (long) page * size;
    }
}
