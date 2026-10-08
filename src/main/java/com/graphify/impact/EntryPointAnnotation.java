package com.graphify.impact;

/** A row of entry_point_annotation (spec §6.2): methods carrying it are reported as entry points. */
public record EntryPointAnnotation(long id, String annotationFqn, String label, boolean enabled) {
}
