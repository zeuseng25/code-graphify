# Plan 12 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Secret target rules (Task 1).** `scmTargetChanged`, `mavenTargetChanged` and `ldapTargetChanged` in `components/secret.ts` decide whether a stored secret must be re-entered. They mirror the backend's same-target rules exactly:
  - values are stripped, and a blank value counts as absent;
  - the comparison is case-sensitive;
  - only the SCM base URL drops a trailing `/`.

  A raw string comparison would have demanded re-entry for a trailing slash or a space.
- **Secret field (Task 1).**
  - `SecretField` has a real label, with the Kayıtlı/Boş badge as its description.
  - A whitespace-only value means keep.
  - Clear mode only applies while a secret is stored.
- **SCM, Maven and LDAP forms (Tasks 2–3).**
  - Test buttons are disabled while the form has unsaved changes or a pending secret change. The SCM and Maven tests use the saved config; the LDAP test sends the form as typed.
  - Deleting a record removes its detail from the cache.
  - Saving LDAP also invalidates the directory search.
- **Users (Task 4).**
  - Changing your own role or active flag refreshes `/me`, so the admin UI disappears at once after a self-demotion.
  - A self role change asks for confirmation.
  - While a change runs, that row's controls are disabled.
- **Final review.**
  - **Typed secrets:** the mutations that carry them use `gcTime: 0` and are reset after success, so no secret lingers in the mutation cache.
  - **Refetches:** `onSettled` returns its invalidation promise, so controls stay locked until the refetch lands. Without this, impact rules could silently revert.
  - **Starting runs:** "Bu bağlantıyı tara" on an enabled connection; disabled connections are left out of the run picker.
  - **Inline errors:** a 409 run conflict shows inline with a link to the running run, from both the start form and the scan buttons.
  - **Audit:** the end of the time range is inclusive.
  - **Validation:** the Maven sort order is checked, and entry-point forms show form-level errors.

## Carry into later work

- **Not tried in a browser:** the real Bitbucket onboarding has not been run end to end in a browser. On the test WildFly: add a connection, test it, run "Bu bağlantıyı tara", then watch the run.
- **Confirmation dialogs:** `ConfirmButton` says the generic "Onayla" and shows no loading state inside its dialog. Disabling LDAP has no confirmation; the backend's 409 lockout guard protects it.
- **Small items:**
  - `ROLE_OPTIONS` lives in `api/users.ts`.
  - `EntryPointsPage` `AddForm` has an indentation slip.
  - There is no mutation-cache secret test for Maven; the code is the same as SCM.
  - A failed save keeps its variables while the page is open (they are dropped when it unmounts).
- **Earlier follow-ups:** the open items from Plans 9–11 are listed in their follow-up docs.
