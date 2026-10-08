# Plan 15 follow-ups

## Rulings
- Checks run only on admin saves, through SettingsAdministration: normalize, then validate, then save, all with the same value. They do not run in AppSettings.update, at startup or for internal callers.
- Directory settings must be absolute. They are normalised lexically (`..` and a trailing slash are removed; symlinks are kept as typed). The directory is created if missing and a probe file is written and deleted. A refused save may still have created missing parent directories.
- The Maven executable is run as `<value> -v`:
  - It gets a List argv (no shell), the indexer's reduced environment and a closed stdin.
  - Its output goes to an owner-only temp file that is never logged; the reason shown is masked.
  - The timeout comes from `index.maven_check_timeout` (V12, PT30S) and is read before the process starts. On timeout the process tree is killed.
- No database connection is held while the checks run.

## Deferred
- **Relative executables containing a `/`** (e.g. `./mvnw`): the check runs in the server's working directory, while indexing runs in each checkout. Such a value is checked against the wrong directory. Options: refuse it, or skip the check and document that it resolves per repository.
- `readLenient` reads the whole output into memory. It should keep only a capped tail.
- Import order in SettingsAdminApiTest; one long README line.
- `..` is resolved before symlinks are followed. This was ruled acceptable: the checked value and the saved value agree.
