# Plan 16 follow-ups

## Rulings and amendments
- Spec §9 (after the Task 3 review):
  - Providers are matched per project root; a repository can self-provision one of its roots for another.
  - Roots are installed in need order and stop at the first failure.
  - Cycles are found with SCC (Tarjan) layering; CYCLE_FAILED applies only to a failing cycle member.
  - Re-indexing uses the transitive provider closure.
  - A GAV the consumer's own repository declares is never taken from another repository.
- A provider outside the run is installed at its default-branch head, not at its last indexed commit, because clones are shallow.
- Any exact GAV match counts, release or SNAPSHOT.
- `last_installed_commit` is advanced only after the index phase, only if the run was not cancelled, and only when every in-run consumer in the provider's closure finished non-FAILED.
- UP_TO_DATE checks presence over every built artifact, by packaging. `present()` is path-contained.
- Outside providers come only from active repositories of enabled connections. A failed checkout gets a row: "Checkout failed: …".
- Within a layer, installs that build a common coordinate run one after another.
- Test fixtures live only under `com.graphify.testfixture.{shop,acme}` in `~/.m2` and are deleted after the tests.
- The install output is read from the CLOB head (4000 characters) in Java, and the cut never splits a surrogate pair.
- Cancelling during the classpath or install loops stops new Maven launches.
- A warning counts `.java` files outside the discovered projects.

## Known limitations (also in the README)
- When a REPOSITORY-scope run installs a new provider, its consumers outside the run are not re-indexed.
- An in-run consumer whose preparation fails does not hold back its provider's `last_installed_commit`.

## Deferred minors
- The Maven timeout applies per run per root, so a repository with N roots can take up to N × `index.maven_timeout`. There is no total cap.
- A pom found inside a root folder that is not one of its modules becomes its own root (for example `src/it`).
- When a GAV is declared twice in the same repository, the first root is chosen, which may cost an extra install.
- Tarjan's recursion is unbounded (about 20k-deep chains overflow).
- The test for need-ordered roots does not discriminate.
- An install that ran before a cancel between phases gets no row.
- A DB error in `recordInstalled` now propagates from `indexAll`.
- The OpenAPI snapshot carries an unrelated reorder of CsrfToken properties.
- A `${…}` packaging is stored uninterpolated.
