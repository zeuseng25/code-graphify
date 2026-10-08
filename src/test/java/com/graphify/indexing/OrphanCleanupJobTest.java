package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrphanCleanupJobTest extends OracleIntegrationTest {

    @Autowired
    OrphanCleanupJob job;

    @Autowired
    IndexLock lock;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, display_signature, origin, name_only)
                VALUES ('p.Orphan', 'CLASS', 'p.Orphan', 'p.Orphan', 'SOURCE', 0)
                """);
    }

    @AfterEach
    void free() {
        lock.forceRelease();
    }

    private int symbols() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class);
    }

    @Test
    void skipsWhileAnIndexRunHoldsTheLock() {
        assertThat(lock.tryAcquire(IndexLock.runHolder(5))).isTrue();

        assertThat(job.run()).isEmpty();

        assertThat(symbols()).isEqualTo(1);
        assertThat(lock.holder()).contains(IndexLock.runHolder(5));
    }

    @Test
    void deletesOrphansUnderTheLockAndReleasesIt() {
        assertThat(job.run()).hasValue(1);

        assertThat(symbols()).isZero();
        assertThat(lock.holder()).isEmpty();
    }
}
