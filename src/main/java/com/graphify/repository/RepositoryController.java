package com.graphify.repository;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.indexing.RepoIndexStatus;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryQueries queries;
    private final PagingResolver paging;

    public RepositoryController(RepositoryQueries queries, PagingResolver paging) {
        this.queries = queries;
        this.paging = paging;
    }

    @GetMapping
    public Page<RepositorySummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return queries.list(q, status(status), paging.resolve(page, size));
    }

    @GetMapping("/{id}")
    public RepositoryDetail get(@PathVariable long id) {
        return queries.find(id).orElseThrow(() -> new NotFoundException("No repository with id " + id));
    }

    /** Upper-cased RepoIndexStatus name, or null when absent; anything else is a 400. */
    private static String status(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RepoIndexStatus.valueOf(status.strip().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("status must be one of " + Arrays.toString(RepoIndexStatus.values()));
        }
    }
}
