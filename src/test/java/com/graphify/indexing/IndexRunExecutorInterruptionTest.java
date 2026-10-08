package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.sql.SQLRecoverableException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Which failures count as the thread being interrupted (shutdown) rather than the work failing. */
class IndexRunExecutorInterruptionTest {

    @Test
    void anInterruptedThreadIsAnInterruptionWhateverItThrew() {
        assertThat(IndexRunExecutor.isInterruption(new IllegalStateException("boom"), true)).isTrue();
    }

    @Test
    void anInterruptedExceptionAnywhereInTheCauseChainIsAnInterruption() {
        assertThat(IndexRunExecutor.isInterruption(new InterruptedException(), false)).isTrue();
        assertThat(IndexRunExecutor.isInterruption(
                new IllegalStateException("git", new RuntimeException(new InterruptedException())), false)).isTrue();
    }

    @Test
    void aJdbcSocketClosedByTheInterruptIsAnInterruption() {
        Throwable jdbc = new DataAccessResourceFailureException("closed",
                new SQLRecoverableException("IO Error", new ClosedByInterruptException()));

        assertThat(IndexRunExecutor.isInterruption(jdbc, false)).isTrue();
        assertThat(IndexRunExecutor.isInterruption(new DataAccessResourceFailureException("closed",
                new SQLRecoverableException("IO Error", new InterruptedIOException("Socket write interrupted"))), false))
                .isTrue();
    }

    @Test
    void ordinaryFailuresAndErrorsAreNotInterruptions() {
        assertThat(IndexRunExecutor.isInterruption(new IllegalStateException("boom"), false)).isFalse();
        assertThat(IndexRunExecutor.isInterruption(new StackOverflowError(), false)).isFalse();
        assertThat(IndexRunExecutor.isInterruption(
                new SQLRecoverableException("IO Error", new SocketTimeoutException("Read timed out")), false)).isFalse();
    }

    @Test
    void aCyclicCauseChainEnds() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(IndexRunExecutor.isInterruption(a, false)).isFalse();
    }
}
