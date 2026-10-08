# Plan 7 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Credentials in URLs (Task 3 → 4, final M1).** SCM `baseUrl`, artifact repository `url` and LDAP `url` reject userinfo with a 400 that never echoes the URL. http(s)/ldap(s) URLs need a host; query and fragment are rejected (SCM, artifact repositories).
- **Connection tests never answer 500 (Task 3 → 4).** Any failure from the client or probe records `FAILED` and answers 502 with a masked message. An artifact repository answering 404 is `FAILED` (final M4).
- **Connection delete (Task 3).** It locks the row and maps a late FK violation to 409. Name clashes map only `DuplicateKeyException` to 409.
- **Repointing an SCM connection (final I1 + residual).**
  - A `baseUrl` change deactivates the connection's repositories in the same transaction. The next sync reactivates the ones the new host lists, with fresh clone URLs.
  - The change takes the index lock (`admin:repoint`), so no run or sync overlaps it. While a run or cleanup is active, the answer is 409.
  - `RepositoryIndexer` reads the clone URL together with the connection's `base_url` and refuses a mismatch. A token is never paired with another host's clone URL.
- **Not done:** the AppPrincipal-backed test base (see the plan), and requiring a passing LDAP test before saving.

## Carry into plan 8 or later

- **Inactive repositories stay in impact results.** Repositories left inactive after a repoint, which the new host does not list, keep their graph data. `RepositoryQueries` does not filter on `active`. Purge them or filter them.
- **Artifact probe:** set `Redirect.NEVER` and count a 3xx as "answered", or add a regression test that a cross-origin redirect drops `Authorization`. Reject `file:` URLs that have an authority (UNC/SMB on Windows). The probe checks `isDirectory` but not `isReadable`.
- **Rows written before Plan 7:** `url`/`baseUrl` values stored before these rules may carry userinfo. Mask them in the views.
- **Run recording:**
  - The recovery insert can hit ORA-00001 against a late worker record, and the DuplicateKey then escapes `tryAcquire`.
  - `recordFailure` after a lost commit acknowledgement throws DuplicateKey and skips `clearMarker`.
- **Lock and indexer robustness:**
  - A holder with a NULL `acquired_at` cannot be taken over.
  - `GitException` must keep having no cause, or git read timeouts would be treated as interruptions. Add a guard comment.
  - `ix_index_run_repo_run` is redundant with `uq_index_run_repo`.
  - The takeover WARN is logged before the fenced update.
- **Consistency:** SCM strips a trailing `/` from `baseUrl`; artifact repositories keep `url` as typed.
- **Tests to add:**
  - an API-level cron reschedule test
  - `SecretUpdate` with multi-byte input
  - entry-point annotation PUT 404/409 and the byte-limit 400s
  - audit rows for annotations and rules
  - a deterministic test of the repoint read race in `RepositoryIndexer`
- **Hardening:** `InvalidSettingValueException` echoes the submitted value (no setting is secret today). The annotation label has no control-character check.
- **Still open from plan 6:** the `register` audit-clash message, sessions surviving a voluntary password change, `LdapDirectory`/login/audit API tests, and the `app_user` schema options.
