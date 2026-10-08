# Plan 8 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Purge of inactive repositories (Task 1, final I1).**
  - The guard's denominator is every indexed repository of the connection, active or inactive, counted before listing. That share stays the same from one sync to the next.
  - A skipped purge is visible: the connection sync status becomes `DEACTIVATION_SKIPPED` and a run note is written. Raising `scm.max_deactivation_percent` releases the purge on the next sync.
- **Cascades and analysis cost (Task 2).**
  - The `ON DELETE CASCADE` symbol foreign keys have `symbol_id` indexes.
  - `dependents` is exact. It uses an iterative Tarjan condensation and int arrays with a reused stamp array.
  - METHOD-level members are only those declared in the repository.
  - `MEMBER_USES` is a `UNION ALL` of the two directions. The declaring-repository lookup is chunked over the external targets.
- **Self-healing analysis (Task 3).**
  - When a repository is skipped as unchanged, its analysis is redone if the stored one is missing or for another commit. This also analyzes repositories indexed before plan 8 on the first run after deploy.
  - The default package is labelled `(default package)` in cycles.
- **Request-time cost (Task 4).**
  - Aggregation stays at request time, as spec §9.1 requires.
  - Without `includeExternal`, edges to external types are filtered out in SQL.
  - At METHOD level, only the classes are loaded, and the full graph is loaded only when the view rolls up.
  - A focus has trailing dots stripped. A focus that matches no class is 404. The default-package focus round-trips.
- **GraphML (Task 5).** XML-illegal code points become U+FFFD. Truncation is marked with graph-level data keys.

## Carry into later plans

- Cache the request-time class graph per (repository, `last_indexed_commit`, includeExternal) if view, report or export latency is measured high on large repositories. The report could take its counts from `repo_graph_analysis`.
- `analyzeGraph` catches only `RuntimeException`. An `OutOfMemoryError` after a successful write marks the repository FAILED, and the self-heal then retries on every run.
- `dependents` is still O(C·E) on long DAG chains (50k+ classes). A cap setting or BitSet reachability would bound it.
- **Bounds:**
  - The member graph of a hot class is loaded in full before the node limit applies.
  - The MODULE level can exceed the limit when there are many external groups.
  - The edge count is not bounded by a setting.
  - The upper bound of `graph.export_max_nodes` (200000) is high.
- There is no read-consistent snapshot across the class graph, the member graph and the stored metrics.
- **Purge guard:** the purge runs before the deactivation guard, and a single stale repository is never guarded (both mirror deactivation).
- METHOD-level library grouping takes `MAX(artifact)` over all of a class's symbols, while the class level uses it per edge.
- **Tests to add:**
  - the analysis-failure path
  - an API test for the `(default package)` focus round trip
  - MODULE with an unknown focus
  - more than one stale repository under the threshold
  - a listed repository with a too-long URL keeps its index
