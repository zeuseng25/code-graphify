package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.audit.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class ReadSnapshotTest extends OracleIntegrationTest {

    private static final String ACTION = "SNAPSHOT_TEST";

    @Autowired
    ReadSnapshot snapshot;

    @Autowired
    AuditLog auditLog;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log WHERE action = ?", ACTION);
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Long.class, ACTION);
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
            Thread writer = new Thread(() -> auditLog.record("tester", ACTION, "target", "committed meanwhile"));
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
