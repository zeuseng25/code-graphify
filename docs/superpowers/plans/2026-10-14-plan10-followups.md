# Plan 10 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Shared helpers (Task 1).**
  - `saveBlob` revokes the object URL after a delay; `filenameOf` reduces names to a basename and returns null for an empty name.
  - `useUrlState` keeps a pending-params ref, because React Router's functional `setParams` does not queue. Updates in the same tick therefore compose.
  - `enumLabel` uses `Object.hasOwn`.
- **Search (Task 2).**
  - Result rows contain real links, which makes them keyboard-reachable and lets them open in a new tab.
  - The `kind` param is validated.
  - Missing repository or location values render `tr.common.none` (`formatRepository`, `formatLocation`).
  - Stale (placeholder) results are dimmed.
- **Impact (Task 3).**
  - The impact query uses `staleTime: Infinity` and never refetches on focus or reconnect. The result renders whenever data exists.
  - Seeds are deduplicated, and depth is clamped to 1..`impactMaxDepth`, both in the URL and in the request.
  - CSV export goes through a mutation, so a 401 signs the user out.
  - Graph roots are plain ids.
- **Final review.**
  - Data-first rendering everywhere: a failed background refetch never replaces shown data.
  - The impact graph and Cytoscape are lazy-loaded; the main chunk went from 1.17 MB to 0.74 MB.
  - Graph labels follow the colour scheme.
  - Global `refetchOnWindowFocus: false`.
  - The usages header shows the filtered total, and run rows show the classpath mode.
  - The "Etki analizi" action is a link.
- **WildFly check.** The final WAR was checked in a browser on WildFly 41 (sign-in, search, the not-found symbol and impact views, repositories, runs, deep-link reloads). The console showed no CSP violations, and the search page did not load Cytoscape. The impact graph itself was not exercised: the index was empty.

## Carry into later plans

- **Repository filter limit:** the search's repository filter loads at most `pageMaxSize` repositories. With more repositories than that, add server-side search (`/repositories?q=`) to the select.
- **Pager:**
  - an out-of-range `?page=` shows an empty list with no way back;
  - Mantine's built-in aria labels are English.
- **Repository select placeholder:** when `?repo=` is not among the summary's repositories, the select shows its placeholder although the filter is applied.
- **Impact form:**
  - "Analiz et" with an unchanged form does not re-run;
  - seeds can only be added through the URL or the symbol page.
- **Run polling:** a persistently failing poll of a RUNNING run keeps polling (paused while the tab is in the background).
- **Plan 11:**
  - the repository graph screen and the "Grafı göster" button;
  - Turkish labels for graph node types and levels;
  - the fcose layout if needed;
  - the lazy-loading pattern from the impact graph.
- **Plan 12:** labels for the user source (`LOCAL`/`LDAP`).
