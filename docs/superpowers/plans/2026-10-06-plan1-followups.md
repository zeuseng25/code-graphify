# Plan 1 follow-ups (carry into plan 2+)

From the plan 1 subagent-driven run. These are the controller rulings and the deferred review findings.

## Must do first in plan 2 (done in plan 2, Task 1)
- DONE: a recovered parameter type spelled qualified (`com.x.T`, `Outer.Inner`) is now keyed from its source spelling (`ITypeBinding.getBinaryName()`), and qualified nested names become `$` binary names.
- RULING (no change): with a single wildcard import, the wildcard keeps precedence over the same package for missing types. A same-package type from the repo's own sources is on the sourcepath and resolves, so a still-missing type is more likely the wildcard's. If wrong: such keys don't join with the classpath-resolved key.
- DONE: `SourceLines.truncate` no longer splits surrogate pairs. Byte-width truncation for Oracle is `Utf8.truncateToBytes` (plan 2, Task 3).

## Rulings
- Ruling: re-review of the message-only amend was a tree-hash comparison (88ce5f0^{tree} == d93a4ab^{tree}) plus reading the new trailer, not a reviewer dispatch — the diff is empty — if wrong: nothing, since content is byte-identical
- Ruling: commit trailers follow the attribution line the committing agent's harness provides, not the plan's fixed Opus line; no history rewrite for T1-T4 — the plan constraint over-specified attribution and the harness line is more accurate about which model wrote the commit — if wrong: cosmetic commit metadata only, fixable by rewording before merge
- Ruling: fix it — ANNOTATION snippet = the annotation's exact source range with whitespace runs (incl. newlines) collapsed to one space; SourceLines keeps the decoded content and FileContext exposes sourceText(ASTNode) — spec/global constraint says "source text" and plan 3 parses endpoint paths from it; the plan's toString() was my defect — if wrong: plan 3 sees one-line normalized text instead of raw multi-line text, harmless for path parsing
- Ruling: C1 — qualify recovered parameter types through the DECLARING top-level type's imports via a repo-wide bindings-off pre-pass (SourceImports); fallback same package if no wildcard imports, else "?Simple" + warning; method bindings with recovered params are RECOVERED — keys must not depend on classpath completeness (spec §4.2) — if wrong: extra pre-parse cost (~bindings-off parse, cheap) and "?" keys in ambiguous wildcard files that don't join cross-repo
- Ruling: I1 — anon subclass emits INSTANTIATION to matched super constructor; interfaces none; missing base → NAME_ONLY #<init>/N — reviewer correct that EXTENDS ≠ constructor usage; reverses my T6 ledger note — if wrong: a few extra INSTANTIATION rows
- Ruling: I2 — receiver-less unresolved call: static single import → class, else enclosing class's superclass name-only, else warning — closes partial-classpath hole — if wrong: misattributed NAME_ONLY to superclass when method actually from an outer class (rare)
- Ruling: I3 — per-batch catch of RuntimeException|StackOverflowError, retry undelivered files singly, failures → warnings; OOM propagates; Files.walk IOException → warning — Review Focus #1 — if wrong: slower re-parse on rare failing batches
- Ruling: fold cheap minors into the fix wave (M1 lowercase receivers, M3 enum ctor args, T7 recovered-field guard, T2 missing tests); defer M2, M4, M5 and the remaining ledger minors — cheap, and they affect correctness of usage data — if wrong: slightly larger fix diff
- Ruling: residual Important — a recovered parameter type spelled qualified in source (`com.x.T`, `Outer.Inner`) gets a wrong same-package key with no warning (MethodKeys.java:54-57); it stems from my C1 ruling using the bare simple name. Not fixed in this branch (no second fix wave): surfaced to the user and carried as the first item of plan 2 — record each parameter's source type text in the SourceImports pre-pass, and fall back to `?` when the spelling is qualified and unresolvable — if wrong: until then, those methods' keys don't join across classpath completeness

## Deferred minors
- Task 1: minor (deferred): JdtParser.parse loops forever if batchSize<=0 (callers validated by IndexerOptions in T5)
- Task 1: minor (deferred): batch test doesn't assert units non-null
- Task 2: minor (deferred): "<init>" literal repeated in SymbolKeys:65,69 and SymbolRegistry:182 — extract a constant
- Task 2: minor (deferred): tests missing for field() incl. array.length null, type() null for primitive/null type, SOURCE-beats-BINARY, type-variable parameter key
- Task 2: minor (deferred): array receiver method (String[].clone()) may key as java.lang.String#clone() — check in usage tasks
- Task 3: minor (deferred): ConfidenceClassifier.of NPEs on null binding — callers (T5-T7 visitors) null-check before calling
- Task 3: minor (deferred): hasErrorWithin is a linear scan per node (fine at normal error counts)
- Task 3: minor (deferred): zero-length node gives end=start-1, may miss a containing error range
- Task 3: minor (deferred): SourceLines.truncate may split a surrogate pair — matters for Oracle storage in plan 2
- Task 4: minor (deferred, plan-mandated code): ImportResolver returns lowercase-dotted names verbatim — qualified nested type keeps dots (not $); binding-less qualified var name `foo.bar` could become a bogus NAME_ONLY class
- Task 4: minor (deferred): duplicate single-type imports with same simple name → last wins instead of ambiguous
- Task 4: minor (deferred): FileContext.usage param `confidence` shadows field
- Task 4: minor (deferred): recovered array type receiver → getName "Foo[]" resolves to garbage
- Task 5: minor (deferred): IOException walking a source root aborts whole index() (UncheckedIOException) instead of warn-and-skip
- Task 5: minor (deferred): file that throws mid-visit leaves partial usages alongside "File skipped" warning
- Task 5: minor (deferred): enum constant bodies, annotation members, record component fields not declared
- Task 5: minor (deferred): TDD RED step skipped by implementer (process only)
- Task 6: minor (deferred): anonymous class creation records no INSTANTIATION — covered by DeclarationVisitor IMPLEMENTS/EXTENDS from enclosing method (T5)
- Task 6: minor (deferred): 4 method-ref visitors repeat warning expression
- Task 6: minor (deferred): RECOVERED test matches key prefix only — deliberate, JDT may pick the wrong overload on an incomplete classpath
- Task 7: minor (deferred): field binding with recovered declaring class not guarded (uncertain reachability)
- Task 7: minor (deferred): NameQualifiedType not visited → no TYPE_REF
- Task 7: minor (deferred): `permits` clause types count as TYPE_REF (undecided)
- Task 7: minor (deferred): malformed multi-byte UTF-8 may give different replacement counts JDT vs String decoder (covered by fallback guard in round 2)
- Task 7: minor (deferred): annotation fallback is a start/end-char heuristic
- Task 8: minor (deferred): batch-size test fixture small (5 files); bare usage() calls don't pin confidence; broken-file call confidence not pinned
- Final: minor (deferred → plan 2): with a single wildcard import, wildcard beats same-package (M2) and now affects declaration keys
- Final: minor (deferred): ?Name warning attributed to the first file that keys the method, at line 0
- Final: minor (deferred): I2 superclass fallback also fires for a resolved superclass (false NAME_ONLY edge in non-compiling code); crosses anonymous/lambda boundaries
- Final: minor (deferred): unreadable-root test errors instead of skipping on non-POSIX filesystems
- Final: minor (deferred): a static import of a nested class member maps to a dotted name, not the $ binary name
- Final: minor (deferred): SourceImports putIfAbsent — when the same FQN is in two modules, the first file's imports win
- Final: minor (deferred): pre-pass rebuilds the JavaCore options per file
