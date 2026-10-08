package com.graphify.search;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.NotFoundException;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/symbols")
public class SymbolController {

    private final SymbolSearch search;
    private final SymbolDetails details;
    private final PagingResolver paging;

    public SymbolController(SymbolSearch search, SymbolDetails details, PagingResolver paging) {
        this.search = search;
        this.details = details;
        this.paging = paging;
    }

    @GetMapping("/search")
    public Page<SymbolHit> search(@RequestParam String q, @RequestParam(required = false) SymbolKind kind,
            @RequestParam(required = false) Long repo, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return search.search(q, kind, repo, paging.resolve(page, size));
    }

    @GetMapping("/{id}")
    public SymbolDetail get(@PathVariable long id) {
        return details.find(id).orElseThrow(() -> notFound(id));
    }

    @GetMapping("/{id}/usages")
    public Page<UsageView> usages(@PathVariable long id, @RequestParam(required = false) Set<Confidence> confidence,
            @RequestParam(required = false) Set<UsageKind> kind, @RequestParam(required = false) Long repo,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        requireSymbol(id);
        return details.usages(id, confidence, kind, repo, paging.resolve(page, size));
    }

    @GetMapping("/{id}/usages/summary")
    public UsageSummary summary(@PathVariable long id) {
        requireSymbol(id);
        return details.summary(id);
    }

    private void requireSymbol(long id) {
        if (!details.exists(id)) {
            throw notFound(id);
        }
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("No symbol with id " + id);
    }
}
