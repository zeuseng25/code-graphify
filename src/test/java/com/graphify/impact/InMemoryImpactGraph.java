package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import com.graphify.indexer.model.UsageKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An impact graph built in a test, so the engine's rules are checked without a database. */
class InMemoryImpactGraph implements ImpactGraph {

    private final Map<Long, ImpactSymbol> symbols = new LinkedHashMap<>();
    private final List<ImpactUsage> usages = new ArrayList<>();
    private final Map<Long, UsageDetail> details = new HashMap<>();
    private final List<AnnotationUse> annotations = new ArrayList<>();
    private final Map<Long, RepoState> modules = new HashMap<>();
    private final Map<Long, List<Long>> declarations = new HashMap<>();
    private long nextId = 1;

    long symbol(String key, SymbolKind kind, Long parentId) {
        return symbol(key, kind, parentId, SymbolOrigin.SOURCE);
    }

    /** A key such as {@code C#m/1} is a name-only twin. */
    long symbol(String key, SymbolKind kind, Long parentId, SymbolOrigin origin) {
        long id = nextId++;
        int hash = key.indexOf('#');
        String classFqn = hash >= 0 ? key.substring(0, hash) : key;
        boolean nameOnly = hash >= 0 && key.indexOf('/', hash) >= 0;
        symbols.put(id, new ImpactSymbol(id, key, kind, key, classFqn, parentId, origin, nameOnly));
        return id;
    }

    void usage(long from, long to, UsageKind kind, Confidence confidence, long moduleId) {
        long id = nextId++;
        usages.add(new ImpactUsage(id, from, to, kind, confidence, moduleId));
        details.put(id, new UsageDetail("F.java", 1, 1, "snippet"));
    }

    void annotation(long symbolId, String annotationKey, String snippet, long moduleId) {
        annotations.add(new AnnotationUse(symbolId, annotationKey, snippet, moduleId));
    }

    void declaration(long symbolId, long moduleId) {
        declarations.computeIfAbsent(symbolId, k -> new ArrayList<>()).add(moduleId);
    }

    void module(long moduleId, String repository, String path, String classpathMode) {
        modules.put(moduleId, new RepoState(moduleId, repository.hashCode(), "TEST", repository, path, classpathMode, "c1",
                null));
    }

    @Override
    public Map<Long, ImpactSymbol> symbols(Collection<Long> ids) {
        Map<Long, ImpactSymbol> found = new LinkedHashMap<>();
        ids.forEach(id -> {
            if (symbols.containsKey(id)) {
                found.put(id, symbols.get(id));
            }
        });
        return found;
    }

    @Override
    public List<Long> descendants(long typeId) {
        List<Long> found = new ArrayList<>();
        for (ImpactSymbol symbol : symbols.values()) {
            if (symbol.parentId() != null && symbol.parentId() == typeId) {
                found.add(symbol.id());
                found.addAll(descendants(symbol.id()));
            }
        }
        return found;
    }

    @Override
    public Map<String, Long> idsOfKeys(Collection<String> keys) {
        Map<String, Long> found = new LinkedHashMap<>();
        symbols.values().stream().filter(s -> keys.contains(s.key())).forEach(s -> found.putIfAbsent(s.key(), s.id()));
        return found;
    }

    @Override
    public List<ImpactUsage> usagesTo(Collection<Long> targetIds, Set<UsageKind> kinds, Set<Confidence> confidences) {
        return usages.stream()
                .filter(u -> targetIds.contains(u.toId()) && kinds.contains(u.kind())
                        && confidences.contains(u.confidence()))
                .toList();
    }

    @Override
    public Map<Long, UsageDetail> usageDetails(Collection<Long> usageIds) {
        Map<Long, UsageDetail> found = new LinkedHashMap<>();
        usageIds.forEach(id -> {
            if (details.containsKey(id)) {
                found.put(id, details.get(id));
            }
        });
        return found;
    }

    @Override
    public Map<Long, List<Long>> overriddenMethods(Collection<Long> symbolIds) {
        Map<Long, List<Long>> found = new LinkedHashMap<>();
        usages.stream().filter(u -> u.kind() == UsageKind.OVERRIDES && symbolIds.contains(u.fromId()))
                .forEach(u -> found.computeIfAbsent(u.fromId(), k -> new ArrayList<>()).add(u.toId()));
        return found;
    }

    @Override
    public Map<Long, List<Long>> overriders(Collection<Long> methodIds) {
        Map<Long, List<Long>> found = new LinkedHashMap<>();
        usages.stream().filter(u -> u.kind() == UsageKind.OVERRIDES && methodIds.contains(u.toId()))
                .forEach(u -> found.computeIfAbsent(u.toId(), k -> new ArrayList<>()).add(u.fromId()));
        return found;
    }

    @Override
    public List<AnnotationUse> annotationsOn(Collection<Long> symbolIds, Collection<String> annotationKeys) {
        return annotations.stream()
                .filter(a -> symbolIds.contains(a.symbolId()) && annotationKeys.contains(a.annotationKey()))
                .toList();
    }

    @Override
    public Map<Long, List<Long>> declaringModules(Collection<Long> symbolIds) {
        Map<Long, List<Long>> found = new LinkedHashMap<>();
        symbolIds.forEach(id -> {
            if (declarations.containsKey(id)) {
                found.put(id, declarations.get(id));
            }
        });
        return found;
    }

    @Override
    public Map<Long, RepoState> repoStates(Collection<Long> moduleIds) {
        Map<Long, RepoState> found = new LinkedHashMap<>();
        moduleIds.forEach(id -> {
            if (modules.containsKey(id)) {
                found.put(id, modules.get(id));
            }
        });
        return found;
    }
}
