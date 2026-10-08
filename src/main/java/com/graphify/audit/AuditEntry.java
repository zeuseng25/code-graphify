package com.graphify.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One audit log entry; {@code action} is an {@link AuditAction} name. */
public record AuditEntry(long id, String actor, @Schema(implementation = AuditAction.class) String action,
        String target, String details, Instant at) {
}
