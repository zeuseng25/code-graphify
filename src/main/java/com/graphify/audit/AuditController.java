package com.graphify.audit;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.InvalidRequestException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit log for admins (spec §10.6 "Audit"); role checks live in SecurityConfiguration. */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AuditController {

    private final AuditQueries queries;
    private final PagingResolver paging;

    public AuditController(AuditQueries queries, PagingResolver paging) {
        this.queries = queries;
        this.paging = paging;
    }

    @GetMapping
    public Page<AuditEntry> list(@RequestParam(required = false) String actor,
            @Parameter(schema = @Schema(implementation = AuditAction.class)) @RequestParam(required = false)
            String action, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return queries.list(actor, action(action), instant("from", from), instant("to", to),
                paging.resolve(page, size));
    }

    /** The action a code names, case-insensitively; an unknown code is a 400, not an empty result. */
    private static AuditAction action(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return AuditAction.parse(code).orElseThrow(() -> new InvalidRequestException(
                "action must be one of " + Arrays.toString(AuditAction.values())));
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(name + " must be an ISO-8601 instant such as 2026-01-31T00:00:00Z");
        }
    }
}
