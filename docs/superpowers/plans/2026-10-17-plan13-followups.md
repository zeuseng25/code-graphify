# Plan 13 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Schema (Task 1).**
  - V10 replaces the `ck_scm_connection_type` CHECK with BITBUCKET_DC/GITHUB/GIT and adds `repository_urls CLOB`.
  - The type is fixed after creation (400).
  - Testing a connection type with no client answers 502, not 500.
- **Git URLs (Task 1 review).** Git URLs must match the base URL's origin and path prefix. Also rejected:
  - percent-encoded dot segments (`%2e%2e`);
  - encoded slashes or backslashes (`%2F`, `%5C`);
  - userinfo, query and fragment.
- **GitHub (Task 2 review).**
  - **Errors:** a rate limit (429, or 403 with `X-RateLimit-Remaining: 0` or `Retry-After`) is a plain ScmException, not AUTH_FAILED. Any non-2xx (including 3xx) fails the listing.
  - **Paging:** next links must stay on the base origin.
  - **Skipped repositories:** archived ones, disabled ones, and any whose `clone_url` is not https (http only with an http base URL), has no host or has userinfo. The latter are logged with a masked WARN.
  - **Organizations:** an empty list fails.
  - **Spec amendment:** spec §3 said "401/403 → ScmAuthenticationException". Rate-limit 403s are now excluded.
- **Git client (Task 3).**
  - JGit follows a cross-host redirect and re-sends the Authorization header. `OriginBoundHttpConnectionFactory` drops Authorization on any request outside the bound origin, and also when no origin is bound (final fix H1).
  - GitHub clones with a blank username use Basic `x-access-token:<token>`.
  - Git listing and test re-validate the stored URLs against the current base URL and fail closed. They reject non-http(s) base URLs; tests widen this through a package-private seam.
- **Checkout directories (final review I1).**
  - A name that `safe()` changes gets a `~` plus 8-hex SHA-256 suffix, so Git project keys with `/` or `%` no longer collide. Bitbucket and GitHub names are unchanged.
  - A checkout whose `remote.origin.url` differs from the requested clone URL is deleted and cloned fresh.
- **UI (Task 4).**
  - The menu, titles and README say "Repo bağlantıları".
  - The form changes per type and clears the old type's fields and errors on a type switch.
  - The type is read-only on edit.
  - `connectionBody` sends only the chosen type's fields.

## Decision after review

- **The `repositoryUrls` limit (decided).** The final fix wave added a 4000-byte limit on the joined list. The user had it removed, because the column is a CLOB; the 1000-byte limit per URL and all URL checks remain.

## Carry into later work

- **Not run against real servers:** GitHub (github.com and Enterprise) and plain Git have not been tried against real servers. The first real run will be on the test WildFly.
- **Small items:**
  - Retry-After given as an HTTP date is still worded "N seconds".
  - On case-insensitive filesystems, Git URLs that differ only in case share a directory. The origin check then re-clones on each switch.
  - Same-host http→https redirects drop credentials. This fails safe, but authentication then fails.
  - Raw percent-escapes are shown in Git project and repository names.
  - Some Git URL pairs derive the same name (`a.git` and `a/a.git`); the second is refused with a 400.
  - `usernameGit` hint wording: it says "secret" where the field label says "Token / şifre".
  - A 403 from Git ls-remote is an ScmException, not an auth failure.
  - Duplicate organizations (`acme`/`ACME`) list repositories twice; sync absorbs this.
