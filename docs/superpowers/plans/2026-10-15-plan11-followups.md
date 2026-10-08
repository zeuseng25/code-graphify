# Plan 11 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Drill-down (Task 1).**
  - Field member nodes (`Class.FIELD` keys) search as `Class#FIELD`.
  - Name-only keys are cut at `/` or `(`.
  - CLASS member nodes (field initializers) search as the class.
  - Element ids are deduplicated.
- **Graph texts follow the user's English-terms ruling (Task 1 onwards).** Task 4's terms test enforces it repo-wide; `imza` was added after the final review. "Signature değişikliği" and "Snippet" were missed by the plan's table.
- **Graph view (Task 2, final review).**
  - Lazy-loaded Cytoscape `cose` layout; the graph is rebuilt only when the data changes.
  - The colour scheme is applied with `cy.style()` and no re-layout. A rebuild uses the current scheme (controller fix).
  - The tapped-edge caption resets on a graph change.
  - The level control highlights the shown level.
- **Report and export (Task 3, final review).**
  - Missing values use `tr.common.none`.
  - Report links keep the page's `includeExternal`.
  - Empty lists show empty-state text.
  - Exports go through a mutation.
- **Navigation.** The graph title links back to the repository page, and the breadcrumb has its own label.

## Carry into later plans

- **A rolled-up module level cannot be drilled.** When the PACKAGE level rolls up to MODULE, tapping a module shows MODULE again, because the backend cannot focus a module. A module focus would need backend support.
- **`cose` layout cost.** The synchronous `cose` layout freezes the tab when `graph.max_nodes` is set far above the default of 500 (its maximum is 5000). If that happens, move to `cytoscape-fcose` or a worker layout.
- **Lower-case `level` in hand-typed URLs** is ignored.
- **Tests to add:** METHOD disabled outside METHOD, a member tap → search, an inert external node, the `includeExternal` toggle, the `notAnalyzed` state, the saved export file name. Both export buttons also spin together during an export.
- **Graph nodes cannot be reached by keyboard.** The report links and the breadcrumb provide keyboard paths instead.
