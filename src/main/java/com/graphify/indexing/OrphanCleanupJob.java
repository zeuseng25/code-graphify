package com.graphify.indexing;

import com.graphify.store.SymbolCleanup;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Deletes orphan symbols under the index lock (spec §4.4, plan 2 follow-up); skipped while an index run holds it. */
@Component
public class OrphanCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanCleanupJob.class);

    private final IndexLock lock;
    private final SymbolCleanup cleanup;

    public OrphanCleanupJob(IndexLock lock, SymbolCleanup cleanup) {
        this.lock = lock;
        this.cleanup = cleanup;
    }

    /** The number of symbols deleted, or empty when the lock was held. */
    public OptionalInt run() {
        if (!lock.tryAcquire(IndexLock.CLEANUP_HOLDER)) {
            log.warn("Orphan symbol cleanup skipped: {} holds the index lock", lock.holder().orElse("nobody"));
            return OptionalInt.empty();
        }
        try {
            int deleted = cleanup.deleteOrphans();
            log.info("Deleted {} orphan symbols", deleted);
            return OptionalInt.of(deleted);
        } finally {
            lock.release(IndexLock.CLEANUP_HOLDER);
        }
    }
}
