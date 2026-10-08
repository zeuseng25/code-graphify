package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.audit.AuditAction;
import com.graphify.audit.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class ReadSnapshotTest extends OracleIntegrationTest {

    /** This test's audit rows are told apart by their actor. */
    private static final String ACTOR = "snapshot-test";

    @Autowired
    ReadSnapshot snapshot;

    @Autowired
    AuditLog auditLog;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log WHERE actor = ?", ACTOR);
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor = ?", Long.class, ACTOR);
    }

    @Test
    void writesAreRejectedInsideTheSnapshot() {
        assertThatThrownBy(() -> snapshot.read(() -> jdbc.update(
                "UPDATE app_setting SET description = description WHERE ROWNUM = 1")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ORA-01456");
    }

    @Test
    void everyQuerySeesTheMomentTheSnapshotStarted() throws Exception {
        long[] seen = snapshot.read(() -> {
            long first = count();
            Thread writer = new Thread(
                    () -> auditLog.record(ACTOR, AuditAction.SETTING_UPDATED, "target", "committed meanwhile"));
            writer.start();
            try {
                writer.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new long[] {first, count()};
        });

        assertThat(seen[1]).isEqualTo(seen[0]);
        assertThat(count()).isEqualTo(seen[0] + 1);
    }
}
