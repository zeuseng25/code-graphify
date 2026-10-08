package com.graphify.impact;

/** An ANNOTATION usage: {@code symbolId} carries {@code annotationKey}, written as {@code snippet}. */
public record AnnotationUse(long symbolId, String annotationKey, String snippet, long moduleId) {
}
