package com.graphify.audit;

import java.time.Instant;

public record AuditEntry(long id, String actor, String action, String target, String details, Instant at) {
}
