package com.graphify.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuditApiTest extends OracleIntegrationTest {

    @Autowired
    AuditLog auditLog;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'AUDIT_TEST%'");
        auditLog.record("alice", "AUDIT_TEST_ONE", "x", "first");
        auditLog.record("bob", "AUDIT_TEST_TWO", "y", "second");
        jdbc.update("UPDATE audit_log SET at = TIMESTAMP '2026-01-01 10:00:00 +00:00' WHERE action = 'AUDIT_TEST_ONE'");
        jdbc.update("UPDATE audit_log SET at = TIMESTAMP '2026-01-02 10:00:00 +00:00' WHERE action = 'AUDIT_TEST_TWO'");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'AUDIT_TEST%'");
    }

    @Test
    void filtersByActorActionAndTimeNewestFirst() {
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=ALICE")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.items[0].action").isEqualTo("AUDIT_TEST_ONE");
            assertThat(json).extractingPath("$.total").isEqualTo(1);
        });
        assertThat(mvc.get().uri("/api/v1/admin/audit?action=audit_test_two")).hasStatusOk().bodyJson()
                .extractingPath("$.items[0].actor").isEqualTo("bob");
        assertThat(mvc.get().uri("/api/v1/admin/audit?from=2026-01-01T00:00:00Z&to=2026-01-03T00:00:00Z"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.items[0].action").isEqualTo("AUDIT_TEST_TWO");
                    assertThat(json).extractingPath("$.items[1].action").isEqualTo("AUDIT_TEST_ONE");
                });
        assertThat(mvc.get().uri("/api/v1/admin/audit?from=2026-01-02T00:00:00Z&to=2026-01-02T10:00:00Z"))
                .hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(0);
    }

    @Test
    void rejectsBadTimesAndNonAdmins() {
        assertThat(mvc.get().uri("/api/v1/admin/audit?from=yesterday")).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/audit")).hasStatus(403);
    }
}
