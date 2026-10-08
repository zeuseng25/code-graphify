package com.graphify.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Spec §8 restart row: at startup, runs left RUNNING by a stopped process become INTERRUPTED and the index lock is
 * released. Runs once every singleton exists (after Flyway) and before the web server accepts requests or
 * {@link IndexScheduler} schedules anything, so it can never interrupt a run of this process.
 */
@Component
public class IndexRunRecovery implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IndexRunRecovery.class);

    private final IndexRunRecorder runs;
    private final IndexLock lock;

    public IndexRunRecovery(IndexRunRecorder runs, IndexLock lock) {
        this.runs = runs;
        this.lock = lock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        recover();
    }

    public void recover() {
        int interrupted = runs.recoverInterrupted();
        lock.forceRelease();
        if (interrupted > 0) {
            log.warn("Marked {} unfinished index run(s) INTERRUPTED", interrupted);
        }
    }
}
