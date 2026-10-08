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
        tearDown();
        auditLog.record("audit-test-alice", AuditAction.LOGIN_SUCCEEDED, "x", "first");
        auditLog.record("audit-test-bob", AuditAction.LOGIN_FAILED, "y", "second");
        auditLog.record("audit-test-a_b", AuditAction.LOGOUT, "z", "underscore");
        auditLog.record("audit-test-axb", AuditAction.LOGOUT, "z", "no underscore");
        jdbc.update("UPDATE audit_log SET at = TIMESTAMP '2026-01-01 10:00:00 +00:00' WHERE actor = 'audit-test-alice'");
        jdbc.update("UPDATE audit_log SET at = TIMESTAMP '2026-01-02 10:00:00 +00:00' WHERE actor = 'audit-test-bob'");
        jdbc.update("UPDATE audit_log SET at = TIMESTAMP '2025-06-01 10:00:00 +00:00' WHERE actor LIKE 'audit-test-a%b'");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM audit_log WHERE actor LIKE 'audit-test-%'");
    }

    @Test
    void filtersByActorActionAndTimeNewestFirst() {
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=AUDIT-TEST-ALICE")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.items[0].action").isEqualTo("LOGIN_SUCCEEDED");
            assertThat(json).extractingPath("$.total").isEqualTo(1);
        });
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=audit-test-&action=login_failed")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.items[0].actor").isEqualTo("audit-test-bob");
                    assertThat(json).extractingPath("$.total").isEqualTo(1);
                });
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=audit-test-&from=2026-01-01T00:00:00Z&to=2026-01-03T00:00:00Z"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.items[0].actor").isEqualTo("audit-test-bob");
                    assertThat(json).extractingPath("$.items[1].actor").isEqualTo("audit-test-alice");
                    assertThat(json).extractingPath("$.total").isEqualTo(2);
                });
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=audit-test-&from=2026-01-02T00:00:00Z&to=2026-01-02T10:00:00Z"))
                .hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(0);
    }

    @Test
    void anActorIsFoundByAnyPartOfItsName() {
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=ALI")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.items[?(@.actor == 'audit-test-alice')]").asArray().hasSize(1);
            assertThat(json).extractingPath("$.items[?(@.actor == 'audit-test-bob')]").asArray().isEmpty();
        });
    }

    @Test
    void wildcardCharactersInTheActorAreMatchedLiterally() {
        // "_" and "%" are LIKE wildcards in SQL; a typed "a_b" must not also find "axb"
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=a_b")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.items[?(@.actor == 'audit-test-a_b')]").asArray().hasSize(1);
            assertThat(json).extractingPath("$.items[?(@.actor == 'audit-test-axb')]").asArray().isEmpty();
        });
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=audit-test-%25")).hasStatusOk().bodyJson()
                .extractingPath("$.total").isEqualTo(0);
    }

    @Test
    void rejectsBadTimesUnknownActionsAndNonAdmins() {
        assertThat(mvc.get().uri("/api/v1/admin/audit?from=yesterday")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/admin/audit?action=NOT_AN_ACTION")).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/audit")).hasStatus(403);
    }

    @Test
    void theApiDescribesEveryAction() {
        assertThat(mvc.get().uri("/api/v1/openapi.json")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.components.schemas.AuditAction.enum").asArray()
                    .contains("LOGIN_SUCCEEDED", "SETTING_UPDATED", "SCM_CONNECTION_DELETED")
                    .hasSize(AuditAction.values().length);
        });
    }
}
