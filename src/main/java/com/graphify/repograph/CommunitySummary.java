package com.graphify.repograph;

/** A detected community: its id, label and number of classes. */
public record CommunitySummary(int id, String label, int size) {
}
