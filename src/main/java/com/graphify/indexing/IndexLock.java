package com.graphify.indexing;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The single index lock of spec §8 (one index run at a time): one row in index_lock, held by an index run
 * ({@code run:<id>}), by the orphan-symbol cleanup ({@code cleanup}) or by an SCM repoint ({@code admin:repoint}). Taking it is one conditional UPDATE, so two
 * callers can never both succeed. Assumes one application instance: startup releases whatever a crashed process held
 * ({@link IndexRunRecovery}), and this process remembers the holders it took. A holder in the row that this process
 * does not know is stale (its release failed, e.g. the database was unreachable when the run ended), so the next
 * acquirer takes the lock over instead of getting a conflict until the next restart.
 */
@Repository
public class IndexLock {

    /** Holder name of the orphan-symbol cleanup job. */
    public static final String CLEANUP_HOLDER = "cleanup";

    /** Holder name of an admin repointing an SCM connection; keeps index runs and syncs out while clone URLs are reset. */
    public static final String REPOINT_HOLDER = "admin:repoint";

    /** The only row, seeded in V5__index_orchestration.sql. */
    private static final String LOCK_NAME = "INDEX";

    /** Prefix of a run holder; the run id follows. */
    private static final String RUN_PREFIX = "run:";

    private static final Logger log = LoggerFactory.getLogger(IndexLock.class);

    private final JdbcTemplate jdbc;
    private final IndexRunRecorder runs;

    /** Holders taken in this process and not yet released; authoritative because there is one instance. */
    private final Set<String> held = ConcurrentHashMap.newKeySet();

    public IndexLock(JdbcTemplate jdbc, IndexRunRecorder runs) {
        this.jdbc = jdbc;
        this.runs = runs;
    }

    public static String runHolder(long runId) {
        return RUN_PREFIX + runId;
    }

    /** The run id when {@code holder} is an index run. */
    public static OptionalLong runIdOf(String holder) {
        if (holder == null || !holder.startsWith(RUN_PREFIX)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(holder.substring(RUN_PREFIX.length())));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Takes the lock when it is free or its holder is stale. A stale run is first ended INTERRUPTED, with its
     * in-progress repositories, as startup recovery would.
     */
    public boolean tryAcquire(String holder) {
        if (!held.add(holder)) { // already held here; added before the UPDATE, so no caller ever sees it as stale
            return false;
        }
        boolean acquired = false;
        try {
            acquired = take(holder) || takeOverStale(holder);
            return acquired;
        } finally {
            if (!acquired) {
                held.remove(holder);
            }
        }
    }

    private boolean take(String holder) {
        return jdbc.update("UPDATE index_lock SET holder = ?, acquired_at = SYSTIMESTAMP "
                + "WHERE lock_name = ? AND holder IS NULL", holder, LOCK_NAME) == 1;
    }

    /** The holder row as one read: its name and when it was taken. */
    private record Holding(String holder, OffsetDateTime acquiredAt) {
    }

    private Optional<Holding> holding() {
        return jdbc.query("SELECT holder, acquired_at FROM index_lock WHERE lock_name = ? AND holder IS NOT NULL",
                (rs, row) -> new Holding(rs.getString("holder"), rs.getObject("acquired_at", OffsetDateTime.class)),
                LOCK_NAME).stream().findFirst();
    }

    private boolean takeOverStale(String holder) {
        Optional<Holding> current = holding();
        if (current.isEmpty()) { // released in the meantime
            return take(holder);
        }
        Holding stale = current.get();
        if (held.contains(stale.holder())) {
            return false;
        }
        log.warn("The index lock holder '{}' is not running in this process; '{}' takes the lock over", stale.holder(),
                holder);
        runIdOf(stale.holder()).ifPresent(runs::recoverRun);
        return takeOver(holder, stale.holder(), stale.acquiredAt()) || take(holder);
    }

    /**
     * Replaces exactly the holding that was judged stale: fenced on the acquisition time, so a holder that re-took the
     * lock under the same name in the meantime (a new cleanup) is never displaced.
     */
    boolean takeOver(String holder, String staleHolder, OffsetDateTime staleAcquiredAt) {
        return jdbc.update("UPDATE index_lock SET holder = ?, acquired_at = SYSTIMESTAMP "
                + "WHERE lock_name = ? AND holder = ? AND acquired_at = ?", holder, LOCK_NAME, staleHolder,
                staleAcquiredAt) == 1;
    }

    public Optional<String> holder() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT holder FROM index_lock WHERE lock_name = ?",
                String.class, LOCK_NAME));
    }

    /** Releases the lock if {@code holder} has it; otherwise a no-op, so a late release never frees someone else's lock. */
    public void release(String holder) {
        try {
            jdbc.update("UPDATE index_lock SET holder = NULL, acquired_at = NULL WHERE lock_name = ? AND holder = ?",
                    LOCK_NAME, holder);
        } finally { // if the UPDATE failed, the row now names a stale holder that the next acquirer takes over
            held.remove(holder);
        }
    }

    /** Startup only: whatever held the lock died with the previous process. */
    public void forceRelease() {
        held.clear();
        jdbc.update("UPDATE index_lock SET holder = NULL, acquired_at = NULL WHERE lock_name = ?", LOCK_NAME);
    }
}
