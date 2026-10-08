# Plan 14 follow-ups

## Rulings
- `includeOwnRepositories` is a nullable Boolean in the update (null = false). The view and the database row are booleans. Existing rows default to 0.
- Own repositories are listed via `/user/repos?affiliation=owner&visibility=all`, so only repositories the token owner owns are included.
  - They skip `includeProjects` but still apply `excludeRepos` as `owner/name`.
  - Organizations are listed first. Duplicates (owner/name compared case-insensitively) keep the first entry.
  - A failure in either listing fails the whole listing.
- The test without organizations is one `GET /user` with no retry.
- A missing organization's 404 now says that a personal account is not an organization and points to `includeOwnRepositories` (found in the final review; this is the user's real case "Zeus").
- One commit trailer (5be55c9) carries the Sonnet line. History was not rewritten.

## Deferred minors
- Tests:
  - the edit form loads a saved flag and re-sends it on PUT;
  - an organization listing that succeeds while the own listing fails throws, naming "user of this token";
  - clone_url hygiene on own repositories;
  - a rate-limited `GET /user`;
  - the audit detail when the flag flips.
- The `pick` test helper is duplicated in `scmConnections.test.tsx`.
- Long lines in `ScmConnections.java`.
- With organizations present, the connection test does not check the own listing; a missing permission only shows at sync.
