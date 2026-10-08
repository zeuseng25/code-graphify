# Plan 3 follow-ups (carry into plans 4–6 and the UI plan)

## Fix first (contract)
- **Dangling twin edges:** frontier NAME_ONLY twins are followed but not emitted as nodes, so edges can reference a toSymbolId with no node. Recommended: add NodeRole.TWIN, emit twins as nodes (nameOnly=true), and exclude them from AFFECTED counts.
- README API section: document nodes[].nameOnly, summary.nodesByConfidence, entryPoints[].http, RepositoryRef objects, and the CSV repository column (projectKey/slug).

## Plan 4
- Denormalized usage_count/repo_count per symbol at index time; search currently computes correlated COUNTs for every hit (I7).
- Fill ImpactResult.versionWarnings from MODULE_DEPENDENCY.
- Run impact BFS in a read-only snapshot (Oracle SET TRANSACTION READ ONLY / SERIALIZABLE; Spring readOnly alone is not a snapshot).

## Plan 5
- impact.max_targets setting (unbounded symbolIds = unauthenticated DoS until auth).
- Pin server.error.include-message=never.

## UI plan
- Page or limit the usages summary and the detail lists (members/subtypes/overriddenBy); hot symbols like Object#toString are huge.

## Rulings
- Ruling: add com.graphify.common.exception.InvalidRequestException extends IllegalArgumentException; ApiExceptionHandler maps only InvalidRequestException (400) and NotFoundException (404); every user-input validation site throws InvalidRequestException — PagingResolver now; later tasks: SymbolQuery.parse (T3), ImpactEngine.Plan (T5), ImpactController export format (T8) — carried in their dispatches. Extending IAE keeps the plan's assertThatIllegalArgumentException tests valid — 500 for internal faults is the correct signal — if wrong: a forgotten validation site returns 500 instead of 400 (tests in T3/T4/T8 catch it)
- Ruling: a lone word (no '.', no '#') is ambiguous: SymbolQuery gains a 4th component `word` (upper-cased; the other components are null). Search matches (search_class = word AND kind is a type kind) OR search_member = word; with a kind filter, (search_class = word OR search_member = word) AND kind = ?. Test expectations change only for lone words: "  resttemplate " and "exchange" → (null,null,null,WORD). The API test sends q via a URI template — this matches what people mean by typing one name — if wrong: a lone word also returns members named like a class (rare, ranked by the same order)
- Ruling: dispatch applies only to symbols reached through a propagating edge (TYPE_REF-only reach gets none) — a symbol that is only type-referenced is not changed in behaviour, so its overridden methods' callers are not affected — if wrong: a few dispatch callers are missed for type-only reach
- Ruling: the engine iterates plan.symbolIds() in request order for seeds; Task 7's adapter orders descendants (ORDER SIBLINGS BY id) and symbols; the ImpactGraph Javadoc states the ordering expectation — stable output for UI and CSV — if wrong: nothing; ordering only
- Ruling: name-only twins are seeded for every callable seed, including the descendants of a type target (a type change should also surface NAME_ONLY callers of its members); null elements in confidences → InvalidRequestException (never 500 for a user mistake) — if wrong: extra "possible" edges for type targets
- Ruling: MappingPaths.path → Optional "resolved path" ("" for a bare annotation or one with no path attribute) or empty = unresolved (path/value is not a pure literal, the positional argument is not a literal, or a literal is followed by anything other than , ) }); if the method or class path is unresolved, httpPath = null (httpMethod kept). The class prefix lookup always includes org.springframework.web.bind.annotation.RequestMapping (Spring fact) besides the label mapping keys. Sort tie-break by label, then snippet. Inherited class-level mapping is deferred (documented in Javadoc as a known limitation) — never show a wrong route, prefer null — if wrong: some routes show null instead of a guess; inherited prefixes are still missing
- Ruling: C1 — never take a BINARY dispatch target (ImpactSymbol gains origin/nameOnly) — JDK/third-party interfaces are where the noise comes from; corporate libs are indexed from source — if wrong: callers via interfaces of non-indexed jars are missed
- Ruling: I1 — prefix ' to free-text CSV cells starting with = + - @ \t \r — standard mitigation — if wrong: a visible apostrophe in some cells
- Ruling: I2 — RepositoryRef(id, projectKey, slug) everywhere a repository appears; summary grouped by id; repo= filter is the repository id — slugs repeat across Bitbucket projects — if wrong: none; contract changes before any UI
- Ruling: I3 — twins added for every callable entering a frontier and for dispatch targets; batch idsOfKeys — NAME_ONLY callers live exactly in partial-classpath repos — if wrong: more "possible" edges
- Ruling: I4 — seed overriders for METHOD targets when SIGNATURE or when the target's parent is an INTERFACE; not for concrete BEHAVIOR — spec §5.2 row 2 — if wrong: an abstract-class method under BEHAVIOR doesn't seed implementations
- Ruling: I5 — ImpactNode gains confidence (weakest-edge path, first-discovery approximation, documented) and nameOnly; summary nodesByConfidence; all Confidence keys emitted — the UI needs to style "possible" nodes — if wrong: a node can show a weaker confidence than its best path
- Ruling: I6 — covering index (to, kind, confidence, from, module, id) edited in V3; light usagesTo plus usageDetails for kept edges only — bounds memory on hot frontiers; V3 still editable — if wrong: one extra query per analysis
- Ruling: I8 — entry annotations inherited from overridden (interface) methods, prefix from that method's class — OpenAPI-generator pattern is pervasive — if wrong: rare duplicate entry points
- Ruling: I9 — rename repositories→staleness, add versionWarnings (empty until plan 4); usagesByLevel replaces levels[] (README) — align with spec before the UI — if wrong: none
- Ruling: fold in T4 exists(), M2 case-insensitive qualified search, M4 EntryPoint.http flag, T8 negative tests; defer I7 (plan 4 denormalized counts), M1 (plan 5), M3/M5-extra/M6/M7, T3 trailing dot, unpaged summary/detail lists (UI plan), T5 seed→seed counting, T7 snapshot tx (plan 4) — cheap now vs contract-affecting — if wrong: deferred items become early tasks in later plans (followups doc)
- Ruling: residual Important — I3's frontier twins are followed but never emitted as nodes, so edges can point at a toSymbolId with no node (dangling edge for a graph UI; the CSV shows a bare id). This comes from my I3 ruling. No second fix wave; surfaced to the user at finish with a recommended fix: emit twins as nodes with NodeRole.TWIN (nameOnly=true), excluded from AFFECTED counts — if wrong: until fixed, the UI must tolerate or filter dangling edges

## Deferred minors
- Task 1: minor (deferred): SchemaMigrationTest index list line too long; search-column test lacks top-level and default-package cases; resolver RED not captured
- Task 2: minor (deferred): Chunks uses fully qualified java.util.Collections
- Task 2: minor (deferred): pin server.error.include-message=never explicitly; the internal-fault test restores hardcoded 50/500 seed values
- Task 3: minor (deferred): "." or "Foo." query yields an empty simple name → 0 results instead of 400
- Task 3: minor (deferred): rejectsTextWithNothingToMatch asserts IAE rather than InvalidRequestException; no test of a word matching both a type and a member
- Task 4: minor (deferred): requireSymbol runs the full find() (~7 queries) as its existence check; add exists(id)
- Task 4: minor (deferred): summary is bounded by distinct using classes (unpaged); detail lists (members/subtypes…) unpaged
- Task 5: minor (deferred): DISPATCH nodes don't count toward maxResults; seed→seed and self-loop edges inflate usage counts; a node's level can predate the start of its propagation
- Task 6: minor (deferred): no test pins the sort tie-break; value = {} gives null rather than ""; inherited class-level mapping not applied (documented)
- Task 7: minor (deferred → plan 4): impact BFS runs without a read-only snapshot transaction; mid-BFS index commits can mix states
- Task 7: minor (deferred): rules test restores the whole entry_point_annotation table instead of the one row; chunking >1000 untested for overriddenMethods/annotationsOn/repoStates
- Task 8: minor (deferred → final review): CSV formula injection — snippets/paths from indexed repos can start with = + - @; prefix ' on free-text cells
- Task 8: minor (deferred): no tests for an empty body, symbolIds:null, or a missing format (all verified 400 by reading the code)
- Final: minor (deferred): a BEHAVIOR request on an interface type seeds members but not their implementations; README misses nodes[].nameOnly, nodesByConfidence, entryPoints[].http and the CSV repository format; no JSON-shape asserts for RepositoryRef in ImpactApiTest; an implementation without a declaration row gets no inherited entry point
