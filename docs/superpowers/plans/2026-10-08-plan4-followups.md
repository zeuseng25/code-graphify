# Plan 4 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Task 6, source roots.** The `index.source_roots` patterns are always added next to any declared `sourceDirectory`/`testSourceDirectory`, and every root is kept inside the checkout. The plan's code dropped the patterns when `sourceDirectory` was set. Cost if wrong: an unused `src/main/java` next to an overridden source directory is indexed as well.
- **Task 7, `settings.xml` ids.** Server, mirror and repository ids carry a random token per run (`graphify-<token>-<id>`). Otherwise a pom in an indexed repository could declare a repository with a predictable id and receive the stored Nexus credentials.
- **Final review C1, SCM listing.** A malformed listing now fails the whole sync, so nothing is deactivated. This covers a null page or values, a non-last page without an advancing `nextPageStart`, and any other `RestClientException`.
- **Final review C2, Maven environment.** The Maven child process gets only the allowlisted environment: `PATH`, `HOME`, `JAVA_HOME`, `LANG`, `LC_ALL`, `TMPDIR`. This keeps `APP_MASTER_KEY` and `DB_PASSWORD` away from `${env.*}` in poms. The allowlist is a named constant, not a setting. Consequence: proxies and `MAVEN_OPTS` must be configured through `artifact_repository` and `settings.xml`, or by extending the constant.
- **Final review I2, partial indexes.** A repository whose last status is `SUCCESS_PARTIAL` is re-indexed even if its commit is unchanged. Cost: a repository that stays partial runs Maven on every run.
- **Final review I3, non-Java repositories.** `SKIPPED_NOT_JAVA` writes an empty index through `replace`, which clears stale rows and records the commit.

## Carry into plan 5 (orchestration)

- The worker must catch every `Throwable` from `RepositoryIndexer.index()` and record `FAILED`. A missing connection and a `default_branch` update failure can still escape.
- Do not index repositories of a disabled connection; `ScmConnections.find` ignores `enabled`.
- Consider a mass-deactivation guard. If a sync would deactivate more than a configurable share of a connection's repositories, warn and skip deactivation. A lost token permission or a mistyped `include_projects` returns an empty but valid listing.
- `GitWorkspace.safe()` must map `"."`, `""` and null to `_` before any workspace cleanup uses `directoryFor`.
- The Bitbucket client creates a new JDK `HttpClient` for each listing and never closes it. Use try-with-resources once syncs recur.
- Log masked reasons for skipped and deactivated repositories, discarded workspaces and skipped dependency rows.
- Cut the outcome error before it is exposed through `/index/runs`; only the stored row is cut today.
- Earlier carry-overs still open: `versionWarnings` from `MODULE_DEPENDENCY`, denormalized search counts, a read-only snapshot for impact, Hikari sizing, chunked orphan cleanup under the lock.

## Deferred (later or opportunistic)

- **Impact twins:**
  - Tests should assert twin confidence and the level of dispatch twins.
  - `addDispatchTarget` ignores `twinLevels`, so a twin's confidence can be overwritten.
- **Maven model hardening:**
  - Containment is lexical only, so symlinks are not caught.
  - The size of expanded properties is not capped.
  - Managed-version lookup uses raw coordinates.
  - An empty `<relativePath/>` is ignored.
  - An inherited `sourceDirectory` and `${project.basedir}` are not supported.
  - A module entry that names a pom file is not supported.
- **Workspace:**
  - A failed update can lead to up to two fresh clones.
  - An update ignores a changed clone URL; this heals itself.
- **Missing tests:**
  - Bitbucket client: 403, 404, I/O errors, Basic auth.
  - `ScmConnections`.
  - Git: the HTTP Authorization header, a non-symbolic HEAD.
  - Hung Maven: that the process actually dies.
  - Maven output: tail masking and line limit, 0600 permissions, temp-file cleanup, mirror plus credentials.
  - Indexer: the lock retry, `last_status` unchanged on `SKIPPED_UNCHANGED`.
