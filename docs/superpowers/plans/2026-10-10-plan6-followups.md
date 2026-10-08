# Plan 6 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Atomic bootstrap admin (Task 2).** The bootstrap admin is created in a `TransactionTemplate`. A self-invoked `@Transactional` would not have applied, and the user/account inserts could have been split. The generated password is logged only after commit.
- **No directory details on the login path (Task 3 → 4).** A directory outage during sign-in answers 502 "The directory is not available", and the detail goes only to the log. Admin-only paths keep the detailed message.
- **Lockout under concurrency (Task 4).**
  - After a lock expires, failures count again from 1.
  - The success path is conditional (`recordSuccessUnlessLocked`), so a parallel burst cannot clear a fresh lock.
  - Every refusal runs one bcrypt check (a dummy hash for unknown users), so timing does not reveal which accounts exist.
- **CSRF hardening (Task 5).** The CSRF token is renewed and the session id changed at sign-in and at password change. The token is accepted only in the `X-XSRF-TOKEN` header. A dead session gets 401 directly.
- **Last-admin lock (Task 6).**
  - Every role or active change takes one `FOR UPDATE` lock that covers the active admins and the target, and decides from that locked state.
  - The bootstrap account needs an active LDAP admin before it can be deactivated, even after it was demoted.
  - Registration does the directory lookup outside the database transaction and maps a unique-key clash to 409.
- **Stored bind password (Task 7).**
  - The stored bind password is reused only when the URL and bind DN are unchanged and a secret is stored. Otherwise the admin must re-enter it.
  - LDAP can be disabled only while an active local admin exists.
  - The URL scheme is validated whenever a URL is given, and the bind password is capped at 1000 bytes.
- **LDAP disabled (final I1, M4).** While LDAP is disabled, only local admins count as "another admin", and directory users' sessions end.
- **Small fixes folded into the final wave:**
  - The request-body records do not print passwords.
  - Passwords over 72 bytes (the bcrypt limit) get a 400, or a clear startup error for `APP_BOOTSTRAP_ADMIN_PASSWORD`.
  - README corrections.

## Carry into plan 7 (remaining admin APIs, lock hardening)

- **Test base:** run as a real `AppPrincipal` tester, so every endpoint test also goes through `CurrentUserRefreshFilter`. Then remove the test-only branches in `AuthController` (`me`, `change-password`).
- **`ExternalSystemException` detail:** review it before the SCM and Maven "test connection" endpoints reuse it.
- **Directory checks:**
  - Optionally require a successful `/admin/ldap/test` before saving a changed URL or bind DN while no active local admin exists.
  - Make `find` behave like `authenticate` on duplicate directory matches.
- **`register`:** a unique-key clash raised by the audit insert is reported as "already registered"; the message should say what actually failed.
- **Sessions:** a voluntary password change does not end the user's other sessions (this needs a session registry).
- **Tests to add:**
  - `LdapDirectory`: anonymous bind, a blank `find`, a wrong bind password on `authenticate`, timeouts.
  - Login service: the stale-snapshot race, the duplicate-key re-read, the reload-first path.
  - Audit API: `from` inclusive, paging, a bad `to`, combined filters.
  - `lockActiveAdmins`-style concurrency for the LDAP disable path.
- **`app_user` schema:**
  - Optionally tie `local_account` to `source = 'LOCAL'`.
  - Index `UPPER(actor)` and `UPPER(action)` on `audit_log` if the volume grows.
- **Earlier carry-overs** from `2026-10-09-plan5-followups.md`:
  - SCM connection admin API with `last_sync_*`
  - artifact-repository, settings, entry-point annotation and impact-rule admin APIs
  - lock-takeover fencing
  - a unique `(run_id, repo_id)` constraint on run results
  - rethrowing interrupts in `RepositoryIndexer`

## Later (React UI / deployment)

- Session cookie `Secure` and `SameSite` flags in the prod profile, once TLS termination is known.
- `anyRequest().denyAll()` must open the static asset paths when the React UI is served from the same origin.
- `GET /auth/csrf` creates a session for anonymous callers; rate-limit at the edge.
- A dead session gets one 401 even on login; the client retries.
- The directory search caps results before sorting.
