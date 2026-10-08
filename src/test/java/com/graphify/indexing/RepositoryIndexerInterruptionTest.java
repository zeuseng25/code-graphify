package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RepositoryIndexerInterruptionTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void interruptionsAreRethrownSoTheRepositoryIsLeftToRecovery() {
        RuntimeException wrapped = new IllegalStateException("git", new ClosedByInterruptException());

        assertThatThrownBy(() -> RepositoryIndexer.rethrowIfInterrupted(wrapped)).isSameAs(wrapped);
        assertThatThrownBy(() -> RepositoryIndexer.rethrowIfInterrupted(
                new IllegalStateException(new InterruptedIOException("maven")))).isInstanceOf(IllegalStateException.class);

        Thread.currentThread().interrupt();
        RuntimeException plain = new IllegalStateException("anything");
        assertThatThrownBy(() -> RepositoryIndexer.rethrowIfInterrupted(plain)).isSameAs(plain);
    }

    @Test
    void ordinaryFailuresAreNotRethrown() {
        assertThatCode(() -> RepositoryIndexer.rethrowIfInterrupted(new IllegalStateException("clone failed")))
                .doesNotThrowAnyException();
    }
}
