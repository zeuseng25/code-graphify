# Plan 17 follow-ups

## Rulings
- Orphan symbols are deleted by `IndexRunExecutor` before `runs.finish`, under the run's index lock, for SUCCESS, FAILED and CANCELLED runs. An interrupted run skips the cleanup.
- An interrupt that arrives during cleanup is cleared so the run's finish is still written, and the flag is restored afterwards.
- A failing cleanup (a RuntimeException) only logs a warning. The run keeps its status, and the weekly `cleanup.orphan_symbols_cron` job remains as the fallback.
- Badge labels in usage tables are no longer clipped (`overflow: visible` on `ConfidenceBadge` and `UsageKindBadge` only). Table cells wrap long keys and paths, and snippets wrap.

## Parked (final review, Minor)
- **Every run scans all symbols.** Each run, even one where every repository was UNCHANGED, scans the whole `symbol` table once (index probes). It could be skipped when the run wrote nothing. This was not done because syncs that deactivate repositories also orphan symbols, so "wrote nothing" needs a careful definition.
- **Cancel during cleanup.** A cancel request is not honoured between cleanup batches.
- **Error from cleanup.** An `Error` (not a RuntimeException) thrown by cleanup escapes `execute`, and `IndexRunService` records the run FAILED.
- **Impact location table styles.** They have no test of their own; the symbol usages table test covers the shared pieces.
