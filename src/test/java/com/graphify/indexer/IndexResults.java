package com.graphify.indexer;

import static java.util.stream.Collectors.joining;

import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.util.List;

/** Lookup helpers that fail with the full usage list, which makes JDT surprises easy to diagnose. */
final class IndexResults {

    private IndexResults() {
    }

    static Symbol symbol(IndexResult result, String key) {
        return result.symbols().stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No symbol " + key + " in:\n"
                        + result.symbols().stream().map(Symbol::key).collect(joining("\n"))));
    }

    static List<Usage> usages(IndexResult result, String fromKey, UsageKind kind, String toKey) {
        return result.usages().stream()
                .filter(u -> u.fromKey().equals(fromKey) && u.kind() == kind && u.toKey().equals(toKey))
                .toList();
    }

    static Usage usage(IndexResult result, String fromKey, UsageKind kind, String toKey) {
        List<Usage> matches = usages(result, fromKey, kind, toKey);
        if (matches.isEmpty()) {
            throw new AssertionError("No " + kind + " usage " + fromKey + " -> " + toKey + ". Usages:\n"
                    + result.usages().stream().map(Usage::toString).collect(joining("\n")));
        }
        return matches.getFirst();
    }

    static List<Usage> usagesTo(IndexResult result, String toKey) {
        return result.usages().stream().filter(u -> u.toKey().equals(toKey)).toList();
    }
}
