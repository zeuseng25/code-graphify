package com.graphify.indexing;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Index runs (spec §10.5). The role checks (ADMIN for start/cancel) live in {@code SecurityConfiguration}. */
@RestController
@RequestMapping("/api/v1")
public class IndexRunController {

    public record StartRequest(RunScope scope, Long id, Boolean force) {
    }

    public record StartedRun(long runId) {
    }

    private final IndexRunService service;
    private final IndexRunQueries queries;
    private final PagingResolver paging;

    public IndexRunController(IndexRunService service, IndexRunQueries queries, PagingResolver paging) {
        this.service = service;
        this.queries = queries;
        this.paging = paging;
    }

    @PostMapping("/index/runs")
    public ResponseEntity<StartedRun> start(@RequestBody(required = false) StartRequest request,
            Authentication authentication) {
        if (request == null) {
            throw new InvalidRequestException("A body {scope, id, force} is required");
        }
        long runId = service.start(request.scope(), request.id(), Boolean.TRUE.equals(request.force()),
                RunTrigger.MANUAL, authentication.getName());
        return ResponseEntity.accepted().location(URI.create("/api/v1/index/runs/" + runId)).body(new StartedRun(runId));
    }

    @GetMapping("/index/runs")
    public Page<IndexRunSummary> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.list(paging.resolve(page, size));
    }

    @GetMapping("/index/runs/{runId}")
    public IndexRunView get(@PathVariable long runId) {
        return queries.find(runId).orElseThrow(() -> new NotFoundException("No index run with id " + runId));
    }

    @PostMapping("/index/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long runId) {
        service.cancel(runId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/repositories/{id}/runs")
    public Page<IndexRunRepoView> repositoryRuns(@PathVariable long id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (!queries.repositoryExists(id)) {
            throw new NotFoundException("No repository with id " + id);
        }
        return queries.repositoryRuns(id, paging.resolve(page, size));
    }
}
