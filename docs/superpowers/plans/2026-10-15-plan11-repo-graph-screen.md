# Plan 11: Repository Graph Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user look at one repository as a graph (spec §5). The screen has:
- module, package, class and method levels;
- drill-down by tapping a node;
- a breadcrumb back up;
- optional external nodes;
- community colours and critical-class sizing;
- the backend's rollup notice;
- a report panel with counts, critical classes, communities, package cycles, entry points and a stale warning;
- GraphML/JSON export.

It also adds the "Grafı göster" link on the repository page.

**Architecture:**
- **Pure helpers in `features/graph/`:**
  - `graphParams.ts`: URL ↔ query, level changes, drill-down targets, breadcrumb, links;
  - `graphElements.ts`: Cytoscape elements carrying presentational data such as colour, size and width.

  Both are unit-tested without a DOM.
- **Data:** `api/repoGraph.ts` holds the query hooks (`useRepoGraph`, `useRepoGraphReport`) and an export mutation built on the shared download helpers.
- **Lazy graph view:** `RepoGraphView` is lazy-loaded exactly like `ImpactGraphView`, so Cytoscape stays out of the main chunk. It uses Cytoscape's built-in `cose` force-directed layout and hands node taps to the page through a stable callback.
- **Page:** `RepoGraphPage` keeps the screen state in the URL (`?level=&focus=&includeExternal=`). It renders data first. Next to the graph it shows `RepoGraphReportPanel`.

**Tech Stack:** React 19, TypeScript 5.9, Mantine 9, React Router 8, TanStack Query 5, openapi-fetch, Cytoscape.js (already installed; no new dependency), Vitest + Testing Library + MSW.

**Spec:**
- `docs/superpowers/specs/2026-10-06-web-ui-design.md` §4.1 row 7, §5, §6, §7 rows 6–7.
- Backend: `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` §9 and §10.5.
- Endpoints (Plan 8):
  - `GET /api/v1/repositories/{id}/graph?level=&focus=&includeExternal=` → `RepoGraph`;
  - `GET /api/v1/repositories/{id}/graph/report` → `RepoGraphReport`;
  - `GET /api/v1/repositories/{id}/graph/export?format=graphml|json&level=&focus=&includeExternal=` → file.

**What the backend does (checked in `repograph/RepoGraphService.java` and `GraphBuilder.java`):**
- **Node ids:** they are `module:<path>`, `package:<name>`, `class:<binary fqn>`, `member:<symbol key>` and `external:<group key>`. Graph nodes carry no symbol id.
- **Focus by level:**
  - MODULE ignores the focus.
  - PACKAGE and CLASS take a package prefix (dot boundary; trailing dots stripped). A prefix matching no class is a 404 "No package …".
  - METHOD requires a class binary name: without one it answers 400 "focus (a class name) is required at METHOD level"; with an undeclared class it answers 404 "No class … is declared".
- **Default package:** the unnamed package is labelled and focused as `(default package)` (`RepoGraphAnalyzer.DEFAULT_PACKAGE`). The service maps that label back to "".
- **Rollup:** a graph over `graph.max_nodes` is rolled up one level and answers `truncated: true`. `level` is the level shown and `requestedLevel` the one asked for; there is also a `suggestion` text. A METHOD rollup focuses the class's package.

**Rulings taken while planning:**
- **Layout is Cytoscape's built-in `cose`**, not `cytoscape-fcose`. The plan's dependency rule allows no new package, and `cose` handles the backend-bounded node count (`graph.max_nodes`). Adding fcose later is a one-line layout change plus a dependency, if layouts prove poor.
- **Drill-down targets:**
  - module node → PACKAGE level without a focus (the backend cannot focus a module);
  - package node → CLASS level with `focus=<package>`; the default package is focused through its label;
  - class node → METHOD level with `focus=<binary fqn>`;
  - member node → symbol search with `q=<class>#<member>` (graph nodes carry no symbol id);
  - external nodes → nothing.
- **Level control:**
  - MODULE, PACKAGE and CLASS are switchable at any time.
  - MODULE drops the focus.
  - Leaving METHOD for PACKAGE or CLASS focuses the class's package.
  - METHOD is reachable only by drill-down or report links, because it needs a class.
- **Edge details are shown below the graph when an edge is tapped**, as Turkish usage-kind counts. Cytoscape has no tooltips, and a tooltip plugin would be a new dependency.
- **Export sends the requested level, focus and `includeExternal`** (the URL), not the shown level. The backend applies its own `graph.export_max_nodes`.
- **The breadcrumb follows what is shown** (the response's `level` and `focus`), so a rolled-up graph does not pretend to be at the requested level.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–8 | Backend | merged |
| 9 | Web foundation | merged |
| 10 | User screens: search, symbol, impact, repositories, runs | merged |
| **11** | **Repository graph screen** (this plan) | — |
| 12 | Admin screens | next |

## Global Constraints

- **Toolchain.**
  - Frontend commands run in `frontend/` with Node 24 and npm 11.
  - `npm test` (`check:api`, typecheck, lint, vitest) and `npm run build` must pass.
  - The backend is not touched in this plan.
- **No new dependency.** Cytoscape is already installed.
- **"Kodda sabit değer yok" (no hardcoded values).**
  - No node limit, page size or interval in code. The backend enforces `graph.max_nodes` and `graph.export_max_nodes`; `/ui-config` `graphMaxNodes` may be displayed.
  - Allowed exceptions:
    - presentational styling: shapes, colours, the community palette, node and edge size formulas, the graph height;
    - protocol facts: the node id prefixes, the `(default package)` focus token, export format names, enum names.
- **Text.**
  - Every user-visible text is in `src/i18n/tr.ts`, in Turkish. Node types and levels go through `tr.enums.nodeType` and `tr.enums.graphLevel`.
  - Backend problem details are shown through `errorMessage()`.
  - A missing value shows `tr.common.none`.
- **Rendering.**
  - Data comes first: render data whenever it exists, `ErrorView` (with retry) only without data, `Loading` otherwise.
  - While placeholder data shows, dim it.
  - Anything that navigates is a real link (`Anchor component={Link}` / `Button component={Link}`), except graph taps.
  - Graph labels stay readable in dark mode (`useComputedColorScheme`, as `ImpactGraphView` does).
- **Routes.** The focus travels in the query string, never in the path: class names contain dots.
- **Types.** API shapes come only from `src/api/schema.d.ts`; response fields are optional.
- **Tests.**
  - MSW for the backend, with `signedIn()` and `renderApp()`.
  - Cytoscape is mocked in page tests; the pure helpers are unit-tested.
  - Assert Turkish text through `tr`.
- **Commits.**
  - Stage `frontend/...` and `README.md` paths explicitly.
  - End every commit with:
    ```
    Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V
    ```

- **Software terms stay in English (user ruling, 2026-10-07).** Words a developer writes in code or reads in the Java/Maven/Git literature are not translated, even inside Turkish sentences. These are:
  - Java and code elements: class, interface, enum, record, annotation, method, constructor, field, package, module, override, extends, implements, signature.
  - Version control and build terms: repository/repo, commit, branch, classpath, dependency, artifact, library.
  - Graph terms: entry point, community, cycle (as "package cycle"), snippet.

  The sentence around them stays Turkish ("3 class etkilendi", "Field okuma"). The mapping is in Task 4.

## Review Focus

1. **A graph link someone shares** (`/repositories/3/graph?level=CLASS&focus=com.shop.api&includeExternal=true`). Opening it requests exactly those parameters, and the export sends the same ones. Tests: Task 2 `repoGraph.test.tsx` "requests the graph the URL describes"; Task 3 `repoGraphReport.test.tsx` "exports the graph the URL describes".
2. **Drilling into a package, then a class, then back up.** Each tap changes only the URL. The breadcrumb leads back to the package and to the package list. Tests: Task 1 `graphParams.test.ts` (drillDown, breadcrumb, levelChange); Task 2 "a node tap drills down through the URL".
3. **A repository larger than the node limit.** The notice shows the backend's suggestion and the level actually shown, and the breadcrumb follows the shown level. Test: Task 2 "a rolled-up graph says so".
4. **A focus that does not exist** (a stale link to a removed package, or a class name at METHOD level). The backend's 404 or 400 message is shown, never a blank or broken graph. Test: Task 2 "shows the backend's message for an unknown focus".
5. **A stale analysis.** The report says so, and its critical classes still link into the graph. Test: Task 3 "the report shows counts, links into the graph and warns when stale".

---

## File Structure (under `frontend/src/` unless noted)

| File | Responsibility |
|---|---|
| `i18n/tr.ts` (modify) | `tr.enums.nodeType`, `tr.enums.graphLevel`, `tr.graph` texts |
| `api/download.ts` (modify), `api/impact.ts` (modify) | Shared `isBlob` (moved from `impact.ts`) |
| `features/graph/graphParams.ts` | URL ↔ query, level change, drill-down, breadcrumb, link building |
| `features/graph/graphElements.ts` | Cytoscape elements, edge kind summary |
| `api/repoGraph.ts` | `useRepoGraph`, `useRepoGraphReport`, `useExportRepoGraph` |
| `features/graph/RepoGraphPage.tsx`, `RepoGraphView.tsx`, `GraphToolbar.tsx`, `GraphBreadcrumb.tsx` | The screen, the lazy graph, controls |
| `features/graph/RepoGraphReportPanel.tsx` | The report |
| `features/repositories/RepositoryPage.tsx` (modify), `routes.tsx` (modify) | "Grafı göster" link and route |
| `README.md` (modify, repository root) | Screen list |

---

### Task 1: Graph URL state, drill-down rules and graph elements

**Files:**
- Create: `frontend/src/features/graph/graphParams.ts`, `frontend/src/features/graph/graphElements.ts`
- Modify: `frontend/src/i18n/tr.ts`, `frontend/src/api/download.ts`, `frontend/src/api/impact.ts`
- Test: `frontend/src/features/graph/graphParams.test.ts`, `frontend/src/features/graph/graphElements.test.ts`

**Interfaces:**
- **Consumes:** `components['schemas']['RepoGraph' | 'GraphNode' | 'GraphEdge']`, `UrlValue` from `hooks/useUrlState`, `enumLabel`, `tr`.
- **Produces:**
  - Types: `GraphLevel` (`'MODULE' | 'PACKAGE' | 'CLASS' | 'METHOD'`) and `GraphQuery { level?: GraphLevel; focus?: string; includeExternal: boolean }`.
  - `graphQueryFrom(params)`:
    - an unknown `level` is dropped;
    - a blank focus is dropped;
    - MODULE drops the focus;
    - `includeExternal` is true only for the value `"true"`.
  - `graphParamsFrom(query)`: URL changes (`includeExternal` is written only when true).
  - `graphHref(repositoryId, query)`: an in-app path with the query string.
  - `packageOf(classFqn)`: the text before the last `.`, or `DEFAULT_PACKAGE_FOCUS` for a class in the unnamed package.
  - `levelChange(query, target)`: the next `GraphQuery`, keeping `includeExternal`.
  - `drillDown(node)`: `{ kind: 'graph', level, focus? } | { kind: 'search', q } | null`.
  - `breadcrumb(shownLevel, shownFocus)`: `{ label, level, focus? }[]`, root first; the last item is the current one.
  - `graphElements(graph)`: `ElementDefinition[]`.
    - Node data: `id`, `label`, `type`, `size` (px), `color`, `entry` (boolean).
    - Edge data: `id`, `source`, `target`, `width`.
  - `edgeKinds(edge)`: Turkish `"Çağrı 3, Tip referansı 1"` text, largest count first.
  - `isBlob(value)`: moved to `api/download.ts`, with `impact.ts` importing it.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/graph/graphParams.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import {
  breadcrumb, DEFAULT_PACKAGE_FOCUS, drillDown, graphHref, graphParamsFrom, graphQueryFrom, levelChange, packageOf,
} from './graphParams';
import { tr } from '../../i18n/tr';

describe('graph URL state', () => {
  it('reads level, focus and external nodes from the URL', () => {
    expect(graphQueryFrom(new URLSearchParams('level=CLASS&focus=com.shop.api&includeExternal=true')))
      .toEqual({ level: 'CLASS', focus: 'com.shop.api', includeExternal: true });
    expect(graphQueryFrom(new URLSearchParams('level=FILE&focus=%20%20'))).toEqual({ includeExternal: false });
    expect(graphQueryFrom(new URLSearchParams('level=MODULE&focus=com.shop'))).toEqual({ level: 'MODULE', includeExternal: false });
  });

  it('writes a query back to the URL and builds links', () => {
    expect(graphParamsFrom({ level: 'METHOD', focus: 'com.shop.api.Checkout', includeExternal: false }))
      .toEqual({ level: 'METHOD', focus: 'com.shop.api.Checkout', includeExternal: null });
    expect(graphHref(3, { level: 'CLASS', focus: 'a.b', includeExternal: true }))
      .toBe('/repositories/3/graph?level=CLASS&focus=a.b&includeExternal=true');
    expect(graphHref(3, { includeExternal: false })).toBe('/repositories/3/graph');
  });

  it('knows the package of a class, including the unnamed one', () => {
    expect(packageOf('com.shop.api.Checkout$Line')).toBe('com.shop.api');
    expect(packageOf('Main')).toBe(DEFAULT_PACKAGE_FOCUS);
  });

  it('changes level keeping what still makes sense', () => {
    const atMethod = { level: 'METHOD' as const, focus: 'com.shop.api.Checkout', includeExternal: true };
    expect(levelChange(atMethod, 'CLASS')).toEqual({ level: 'CLASS', focus: 'com.shop.api', includeExternal: true });
    expect(levelChange(atMethod, 'PACKAGE')).toEqual({ level: 'PACKAGE', focus: 'com.shop.api', includeExternal: true });
    expect(levelChange({ level: 'CLASS', focus: 'com.shop', includeExternal: false }, 'MODULE'))
      .toEqual({ level: 'MODULE', includeExternal: false });
    expect(levelChange({ level: 'PACKAGE', focus: 'com.shop', includeExternal: false }, 'CLASS'))
      .toEqual({ level: 'CLASS', focus: 'com.shop', includeExternal: false });
  });
});

describe('drill-down', () => {
  it('goes one level down from each internal node and nowhere from external ones', () => {
    expect(drillDown({ id: 'module:shop-api', label: 'shop-api', type: 'MODULE' })).toEqual({ kind: 'graph', level: 'PACKAGE' });
    expect(drillDown({ id: 'package:com.shop.api', label: 'com.shop.api', type: 'PACKAGE' }))
      .toEqual({ kind: 'graph', level: 'CLASS', focus: 'com.shop.api' });
    expect(drillDown({ id: 'package:', label: DEFAULT_PACKAGE_FOCUS, type: 'PACKAGE' }))
      .toEqual({ kind: 'graph', level: 'CLASS', focus: DEFAULT_PACKAGE_FOCUS });
    expect(drillDown({ id: 'class:com.shop.api.Checkout$Line', label: 'com.shop.api.Checkout$Line', type: 'CLASS' }))
      .toEqual({ kind: 'graph', level: 'METHOD', focus: 'com.shop.api.Checkout$Line' });
    expect(drillDown({ id: 'member:com.shop.api.Checkout#total(int)', label: 'int total(int)', type: 'METHOD' }))
      .toEqual({ kind: 'search', q: 'com.shop.api.Checkout#total' });
    expect(drillDown({ id: 'external:repo:SHOP/lib', label: 'SHOP/lib', type: 'EXTERNAL_REPOSITORY' })).toBeNull();
  });
});

describe('breadcrumb', () => {
  it('leads back from a class to its package and to the package list', () => {
    expect(breadcrumb('METHOD', 'com.shop.api.Checkout')).toEqual([
      { label: tr.graph.crumbs.modules, level: 'MODULE' },
      { label: tr.graph.crumbs.packages, level: 'PACKAGE' },
      { label: 'com.shop.api', level: 'CLASS', focus: 'com.shop.api' },
      { label: 'Checkout', level: 'METHOD', focus: 'com.shop.api.Checkout' },
    ]);
    expect(breadcrumb('CLASS', 'com.shop')).toEqual([
      { label: tr.graph.crumbs.modules, level: 'MODULE' },
      { label: tr.graph.crumbs.packages, level: 'PACKAGE' },
      { label: 'com.shop', level: 'CLASS', focus: 'com.shop' },
    ]);
    expect(breadcrumb('CLASS', undefined)).toEqual([
      { label: tr.graph.crumbs.modules, level: 'MODULE' },
      { label: tr.graph.crumbs.packages, level: 'PACKAGE' },
      { label: tr.graph.crumbs.classes, level: 'CLASS' },
    ]);
    expect(breadcrumb('MODULE', undefined)).toEqual([{ label: tr.graph.crumbs.modules, level: 'MODULE' }]);
  });
});
```

`frontend/src/features/graph/graphElements.test.ts`:

```ts
import { expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { edgeKinds, graphElements } from './graphElements';

const graph = {
  nodes: [
    { id: 'class:a.A', label: 'a.A', type: 'CLASS' as const, size: 1,
      metrics: { dependents: 0, communityId: 1, entryPoint: false } },
    { id: 'class:a.B', label: 'a.B', type: 'CLASS' as const, size: 1,
      metrics: { dependents: 15, communityId: 2, entryPoint: true } },
    { id: 'external:lib:x', label: 'x', type: 'EXTERNAL_LIBRARY' as const, size: 3 },
  ],
  edges: [{ from: 'class:a.A', to: 'class:a.B', weight: 4, kinds: { CALL: 3, TYPE_REF: 1 } }],
};

it('turns a repository graph into styled elements', () => {
  const elements = graphElements(graph);
  const node = (id: string) => elements.find((e) => e.data.id === id)!.data;

  expect(node('class:a.B').size).toBeGreaterThan(node('class:a.A').size);
  expect(node('class:a.A').color).not.toBe(node('class:a.B').color);
  expect(node('class:a.B').entry).toBe(true);
  expect(node('external:lib:x').type).toBe('EXTERNAL_LIBRARY');
  const edge = elements.find((e) => e.data.id === 'class:a.A->class:a.B')!.data;
  expect(edge).toMatchObject({ source: 'class:a.A', target: 'class:a.B' });
  expect(edge.width).toBeGreaterThan(1);
});

it('drops edges whose ends are not in the graph', () => {
  const elements = graphElements({ nodes: graph.nodes, edges: [{ from: 'class:a.A', to: 'class:gone', weight: 1 }] });
  expect(elements.filter((e) => e.data.source)).toHaveLength(0);
});

it('summarises an edge in Turkish, largest count first', () => {
  expect(edgeKinds(graph.edges[0])).toBe(`${tr.enums.usageKind.CALL} 3, ${tr.enums.usageKind.TYPE_REF} 1`);
  expect(edgeKinds({})).toBe(tr.common.none);
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test` (in `frontend/`)
Expected: failures; the modules do not exist yet.

- [ ] **Step 3: Implement**

Add to `tr.ts`:

```ts
  graph: {
    show: 'Grafı göster',
    title: (repository: string) => `${repository} — graf`,
    level: 'Seviye',
    includeExternal: 'Dış bağımlılıkları göster',
    export: { graphml: 'GraphML indir', json: 'JSON indir' },
    exportFileName: (level: string, extension: string) => `repo-grafi-${level}.${extension}`,
    crumbs: { modules: 'Modüller', packages: 'Paketler', classes: 'Sınıflar' },
    truncated: (shown: string) => `Graf büyük olduğu için ${shown} seviyesinde gösteriliyor.`,
    empty: 'Bu seviyede gösterilecek düğüm yok.',
    edgeInfo: (from: string, to: string, kinds: string) => `${from} → ${to}: ${kinds}`,
    tapHint: 'Bir düğüme tıklayarak bir alt seviyeye inin; bir kenara tıklayarak kullanım türlerini görün.',
    report: {
      title: 'Rapor',
      stale: (indexed: string, analyzed: string) =>
        `Analiz eski: son taranan commit ${indexed}, analiz edilen ${analyzed}.`,
      notAnalyzed: 'Bu repo için henüz graf analizi yok.',
      analyzedAt: 'Analiz zamanı',
      counts: {
        modules: 'Modül', packages: 'Paket', classes: 'Sınıf', dependencies: 'Bağımlılık', communities: 'Topluluk',
        entryPoints: 'Giriş noktası',
      },
      critical: 'Kritik sınıflar',
      criticalColumns: { name: 'Sınıf', dependents: 'Bağımlı', inOut: 'Giren / çıkan' },
      openSymbol: 'Sembol',
      entryPoint: 'Giriş noktası',
      communities: 'Topluluklar',
      communitySize: (size: string) => `${size} sınıf`,
      cycles: 'Paket döngüleri',
      noCycles: 'Paketler arasında döngü yok.',
      cycleSeparator: ' ↔ ',
      entryPoints: 'Giriş noktası sınıfları',
    },
  },
```

Under `enums`:

```ts
    nodeType: {
      MODULE: 'Module', PACKAGE: 'Package', CLASS: 'Class', METHOD: 'Method', CONSTRUCTOR: 'Constructor', FIELD: 'Field',
      EXTERNAL_REPOSITORY: 'Dış repository', EXTERNAL_LIBRARY: 'Dış library',
    },
    graphLevel: { MODULE: 'Module', PACKAGE: 'Package', CLASS: 'Class', METHOD: 'Method' },
```

`frontend/src/api/download.ts`: add and export `isBlob`, moved from `impact.ts` without changes. Import it in `impact.ts`.

```ts
/** Tag check instead of instanceof: a Blob from another realm (jsdom tests) is still a Blob. */
export function isBlob(value: unknown): value is Blob {
  return Object.prototype.toString.call(value) === '[object Blob]';
}
```

`frontend/src/features/graph/graphParams.ts`:

```ts
import type { components } from '../../api/schema';
import type { UrlValue } from '../../hooks/useUrlState';
import { tr } from '../../i18n/tr';

export type GraphLevel = NonNullable<components['schemas']['RepoGraph']['level']>;
type GraphNode = components['schemas']['GraphNode'];

export const LEVELS: readonly GraphLevel[] = ['MODULE', 'PACKAGE', 'CLASS', 'METHOD'];

/** The backend's name and focus token for the unnamed package (RepoGraphAnalyzer.DEFAULT_PACKAGE). */
export const DEFAULT_PACKAGE_FOCUS = '(default package)';

/** Node id prefixes of the graph API (repograph/GraphBuilder). */
const MODULE_ID = 'module:';
const PACKAGE_ID = 'package:';
const CLASS_ID = 'class:';
const MEMBER_ID = 'member:';

export interface GraphQuery {
  level?: GraphLevel;
  focus?: string;
  includeExternal: boolean;
}

export type DrillTarget = { kind: 'graph'; level: GraphLevel; focus?: string } | { kind: 'search'; q: string };

export interface Crumb {
  label: string;
  level: GraphLevel;
  focus?: string;
}

/** The graph a URL describes; unknown levels and blank focuses are dropped, MODULE takes no focus. */
export function graphQueryFrom(params: URLSearchParams): GraphQuery {
  const level = LEVELS.find((value) => value === params.get('level'));
  const focus = params.get('focus')?.trim() || undefined;
  const query: GraphQuery = { includeExternal: params.get('includeExternal') === 'true' };
  if (level) {
    query.level = level;
  }
  if (focus && level !== 'MODULE') {
    query.focus = focus;
  }
  return query;
}

export function graphParamsFrom(query: GraphQuery): Record<string, UrlValue> {
  return { level: query.level, focus: query.focus, includeExternal: query.includeExternal ? 'true' : null };
}

export function graphHref(repositoryId: number, query: GraphQuery): string {
  const params = new URLSearchParams();
  if (query.level) params.set('level', query.level);
  if (query.focus) params.set('focus', query.focus);
  if (query.includeExternal) params.set('includeExternal', 'true');
  const search = params.toString();
  return `/repositories/${repositoryId}/graph${search ? `?${search}` : ''}`;
}

/** The package of a binary class name; the unnamed package has the backend's token. */
export function packageOf(classFqn: string): string {
  const dot = classFqn.lastIndexOf('.');
  return dot < 0 ? DEFAULT_PACKAGE_FOCUS : classFqn.slice(0, dot);
}

/** The level control: MODULE takes no focus; leaving METHOD focuses the class's package. */
export function levelChange(query: GraphQuery, target: GraphLevel): GraphQuery {
  if (target === 'MODULE') {
    return { level: 'MODULE', includeExternal: query.includeExternal };
  }
  const focus = query.level === 'METHOD' && query.focus ? packageOf(query.focus) : query.focus;
  return focus ? { level: target, focus, includeExternal: query.includeExternal }
    : { level: target, includeExternal: query.includeExternal };
}

/** Where a tap on a node leads; graph nodes carry no symbol id, so members open the symbol search. */
export function drillDown(node: Pick<GraphNode, 'id' | 'label'>): DrillTarget | null {
  const id = node.id ?? '';
  if (id.startsWith(MODULE_ID)) {
    return { kind: 'graph', level: 'PACKAGE' };
  }
  if (id.startsWith(PACKAGE_ID)) {
    const name = id.slice(PACKAGE_ID.length) || node.label;
    return name ? { kind: 'graph', level: 'CLASS', focus: name } : null;
  }
  if (id.startsWith(CLASS_ID)) {
    const fqn = id.slice(CLASS_ID.length);
    return fqn ? { kind: 'graph', level: 'METHOD', focus: fqn } : null;
  }
  if (id.startsWith(MEMBER_ID)) {
    const q = id.slice(MEMBER_ID.length).split('(')[0];
    return q ? { kind: 'search', q } : null;
  }
  return null;
}

/** The way back up from what is shown (the response's level and focus, which may be a rollup). */
export function breadcrumb(level: GraphLevel | undefined, focus: string | undefined): Crumb[] {
  const crumbs: Crumb[] = [{ label: tr.graph.crumbs.modules, level: 'MODULE' }];
  if (!level || level === 'MODULE') {
    return crumbs;
  }
  crumbs.push({ label: tr.graph.crumbs.packages, level: 'PACKAGE' });
  if (level === 'PACKAGE') {
    if (focus) crumbs.push({ label: focus, level: 'PACKAGE', focus });
    return crumbs;
  }
  if (level === 'CLASS') {
    crumbs.push(focus ? { label: focus, level: 'CLASS', focus } : { label: tr.graph.crumbs.classes, level: 'CLASS' });
    return crumbs;
  }
  if (focus) {
    const pkg = packageOf(focus);
    crumbs.push({ label: pkg, level: 'CLASS', focus: pkg });
    crumbs.push({ label: focus.slice(focus.lastIndexOf('.') + 1), level: 'METHOD', focus });
  }
  return crumbs;
}
```

At PACKAGE level with a focus, the test above has no case; the PACKAGE branch keeps the prefix crumb. Add a test case for it: `breadcrumb('PACKAGE', 'com.shop')` gives `[modules, packages, { label: 'com.shop', level: 'PACKAGE', focus: 'com.shop' }]`.

`frontend/src/features/graph/graphElements.ts`:

```ts
import type { ElementDefinition } from 'cytoscape';
import type { components } from '../../api/schema';
import { enumLabel } from '../../i18n/enumLabel';
import { tr } from '../../i18n/tr';

type RepoGraph = Pick<components['schemas']['RepoGraph'], 'nodes' | 'edges'>;
type GraphEdge = components['schemas']['GraphEdge'];

/** Presentational: distinguishable community colours, cycled when there are more communities. */
const COMMUNITY_PALETTE = ['#4c6ef5', '#12b886', '#fab005', '#e64980', '#7950f2', '#15aabf', '#fd7e14', '#82c91e',
  '#be4bdb', '#228be6'];
/** Presentational: nodes without a community (modules, packages, members, external nodes). */
const TYPE_COLOR: Record<string, string> = {
  MODULE: '#495057', PACKAGE: '#5c7cfa', METHOD: '#20c997', CONSTRUCTOR: '#20c997', FIELD: '#94d82d',
  EXTERNAL_REPOSITORY: '#adb5bd', EXTERNAL_LIBRARY: '#ced4da',
};
/** Presentational node sizing in px: a base plus a logarithmic share of what the node stands for. */
const BASE_SIZE = 16;
const SIZE_STEP = 6;

function nodeSize(weight: number | undefined): number {
  return BASE_SIZE + SIZE_STEP * Math.log2(1 + Math.max(0, weight ?? 0));
}

export function graphElements(graph: RepoGraph): ElementDefinition[] {
  const nodes: ElementDefinition[] = (graph.nodes ?? []).filter((node) => node.id).map((node) => {
    const metrics = node.metrics;
    const community = metrics?.communityId;
    return {
      group: 'nodes',
      data: {
        id: node.id!,
        label: node.label ?? node.id!,
        type: node.type ?? '',
        size: nodeSize(metrics ? metrics.dependents : node.size),
        color: community != null
          ? COMMUNITY_PALETTE[(community - 1 + COMMUNITY_PALETTE.length) % COMMUNITY_PALETTE.length]
          : TYPE_COLOR[node.type ?? ''] ?? TYPE_COLOR.PACKAGE,
        entry: metrics?.entryPoint === true,
      },
    };
  });
  const ids = new Set(nodes.map((node) => node.data.id));
  const edges: ElementDefinition[] = [];
  for (const edge of graph.edges ?? []) {
    if (!edge.from || !edge.to || !ids.has(edge.from) || !ids.has(edge.to)) {
      continue;
    }
    edges.push({
      group: 'edges',
      data: { id: `${edge.from}->${edge.to}`, source: edge.from, target: edge.to, width: 1 + Math.log2(Math.max(1, edge.weight ?? 1)) },
    });
  }
  return [...nodes, ...edges];
}

/** "Çağrı 3, Tip referansı 1": an edge's usage kinds in Turkish, largest first. */
export function edgeKinds(edge: Pick<GraphEdge, 'kinds'>): string {
  const entries = Object.entries(edge.kinds ?? {}).sort((a, b) => b[1] - a[1]);
  return entries.length
    ? entries.map(([kind, count]) => `${enumLabel(tr.enums.usageKind, kind)} ${count}`).join(', ')
    : tr.common.none;
}
```

The counts in `edgeKinds` are plain integers taken from the API; this matches the unit test. If you prefer `formatNumber`, update the test's expected text accordingly.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass. The impact tests still pass with `isBlob` moved.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): repository graph URL state, drill-down rules and graph elements" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 2: The graph screen

**Files:**
- Create: `frontend/src/api/repoGraph.ts` (the `useRepoGraph` part), `frontend/src/features/graph/RepoGraphPage.tsx`, `RepoGraphView.tsx`, `GraphToolbar.tsx`, `GraphBreadcrumb.tsx`
- Modify: `frontend/src/routes.tsx`, `frontend/src/features/repositories/RepositoryPage.tsx`
- Test: `frontend/src/features/graph/repoGraph.test.tsx`

**Interfaces:**
- **Consumes:** Task 1's helpers; `useRepository` (title); `useUrlState`; `ErrorView`, `Loading`; `GET /api/v1/repositories/{id}/graph`.
- **Produces:**
  - `useRepoGraph(id, query)`: `keepPreviousData`; the query key includes `query`.
  - Route `/repositories/:id/graph`. An invalid id renders `NotFoundPage`.
  - `RepoGraphView` is the default export, lazy-loaded with `data-testid="repo-graph"`. Its props are `{ graph, onNode(id) }`; it shows the tapped edge's `edgeInfo` below the graph.
  - `GraphToolbar`:
    - a `SegmentedControl` over `tr.enums.graphLevel`, with METHOD disabled unless the shown level is METHOD;
    - a `Switch` for `includeExternal`;
    - two export buttons (wired in Task 3; Task 2 renders them disabled).
  - `GraphBreadcrumb`: Mantine `Breadcrumbs`. Every crumb except the last is an `Anchor component={Link}` to `graphHref(id, …)`.
  - On `RepositoryPage`: `Button component={Link} to=/repositories/{id}/graph` labelled `tr.graph.show`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/graph/repoGraph.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import cytoscape from 'cytoscape';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

const mocks = vi.hoisted(() => ({ handlers: [] as { event: string; selector: string; fn: (event: unknown) => void }[] }));
vi.mock('cytoscape', () => ({
  default: vi.fn(() => ({
    on: vi.fn((event: string, selector: string, fn: (event: unknown) => void) => mocks.handlers.push({ event, selector, fn })),
    destroy: vi.fn(),
  })),
}));

const summary = { id: 3, projectKey: 'SHOP', slug: 'api', lastStatus: 'SUCCESS', active: true, moduleCount: 1 };

function graphBody(overrides: Record<string, unknown> = {}) {
  return {
    repositoryId: 3, requestedLevel: 'CLASS', level: 'CLASS', focus: 'com.shop.api', truncated: false,
    nodes: [
      { id: 'class:com.shop.api.Checkout', label: 'com.shop.api.Checkout', type: 'CLASS', size: 1,
        metrics: { inDegree: 1, outDegree: 2, dependents: 4, entryPoint: true, communityId: 1, communityLabel: 'com.shop.api' } },
      { id: 'class:com.shop.api.Cart', label: 'com.shop.api.Cart', type: 'CLASS', size: 1 },
    ],
    edges: [{ from: 'class:com.shop.api.Checkout', to: 'class:com.shop.api.Cart', weight: 3, kinds: { CALL: 3 } }],
    ...overrides,
  };
}

function graphBackend(options: { requests?: URL[]; body?: unknown; status?: number } = {}) {
  signedIn();
  server.use(
    http.get(apiUrl('/api/v1/repositories/3'), () => HttpResponse.json({ repository: summary, modules: [] })),
    http.get(apiUrl('/api/v1/repositories/3/runs'), () => HttpResponse.json({ items: [], page: 0, size: 50, total: 0 })),
    http.get(apiUrl('/api/v1/repositories/3/graph/report'), () => HttpResponse.json({ repositoryId: 3, stale: false })),
    http.get(apiUrl('/api/v1/repositories/3/graph'), ({ request }) => {
      options.requests?.push(new URL(request.url));
      return HttpResponse.json(options.body ?? graphBody(), { status: options.status ?? 200 });
    }),
  );
}

describe('repository graph', () => {
  it('requests the graph the URL describes and draws it lazily', async () => {
    const requests: URL[] = [];
    graphBackend({ requests });
    renderApp('/repositories/3/graph?level=CLASS&focus=com.shop.api&includeExternal=true');

    expect(await screen.findByTestId('repo-graph')).toBeInTheDocument();
    const sent = requests.at(-1)!.searchParams;
    expect(sent.get('level')).toBe('CLASS');
    expect(sent.get('focus')).toBe('com.shop.api');
    expect(sent.get('includeExternal')).toBe('true');
    await waitFor(() => expect(vi.mocked(cytoscape)).toHaveBeenCalled());
    const elements = vi.mocked(cytoscape).mock.calls.at(-1)![0]!.elements as { data: { id: string } }[];
    expect(elements.map((e) => e.data.id)).toContain('class:com.shop.api.Checkout');
  });

  it('a node tap drills down through the URL and the breadcrumb leads back', async () => {
    graphBackend();
    const router = renderApp('/repositories/3/graph?level=CLASS&focus=com.shop.api');
    await screen.findByTestId('repo-graph');
    await waitFor(() => expect(mocks.handlers.some((h) => h.event === 'tap' && h.selector === 'node')).toBe(true));

    mocks.handlers.filter((h) => h.event === 'tap' && h.selector === 'node').at(-1)!
      .fn({ target: { id: () => 'class:com.shop.api.Checkout' } });

    await waitFor(() => expect(router.state.location.search).toBe('?level=METHOD&focus=com.shop.api.Checkout'));
    const crumbs = screen.getByRole('navigation', { name: tr.graph.level });
    expect(within(crumbs).getByRole('link', { name: tr.graph.crumbs.packages }))
      .toHaveAttribute('href', '/repositories/3/graph?level=PACKAGE');
  });

  it('switches level keeping what still makes sense', async () => {
    graphBackend();
    const router = renderApp('/repositories/3/graph?level=CLASS&focus=com.shop.api');
    await screen.findByTestId('repo-graph');

    await userEvent.click(screen.getByRole('radio', { name: tr.enums.graphLevel.MODULE }));

    await waitFor(() => expect(router.state.location.search).toBe('?level=MODULE'));
  });

  it('a rolled-up graph says so and the breadcrumb follows what is shown', async () => {
    graphBackend({ body: graphBody({
      requestedLevel: 'CLASS', level: 'PACKAGE', focus: undefined, truncated: true,
      suggestion: '7200 class nodes exceed the node limit (500); shown by package, narrow it with focus',
      nodes: [{ id: 'package:com.shop.api', label: 'com.shop.api', type: 'PACKAGE', size: 7200 }], edges: [],
    }) });
    renderApp('/repositories/3/graph?level=CLASS');

    expect(await screen.findByText(tr.graph.truncated(tr.enums.graphLevel.PACKAGE))).toBeInTheDocument();
    expect(screen.getByText(/7200 class nodes exceed the node limit/)).toBeInTheDocument();
    const crumbs = screen.getByRole('navigation', { name: tr.graph.level });
    expect(within(crumbs).queryByText(tr.graph.crumbs.classes)).not.toBeInTheDocument();
  });

  it('shows the backend message for an unknown focus', async () => {
    graphBackend({ body: { status: 404, detail: 'No package com.gone in repository 3' }, status: 404 });
    renderApp('/repositories/3/graph?level=CLASS&focus=com.gone');

    expect(await screen.findByText('No package com.gone in repository 3')).toBeInTheDocument();
    expect(screen.queryByTestId('repo-graph')).not.toBeInTheDocument();
  });

  it('the repository page links to its graph', async () => {
    graphBackend();
    renderApp('/repositories/3');

    expect(await screen.findByRole('link', { name: tr.graph.show })).toHaveAttribute('href', '/repositories/3/graph');
  });

  it('a non-numeric repository id is not found', async () => {
    signedIn();
    renderApp('/repositories/abc/graph');

    expect(await screen.findByText(tr.errors.notFound)).toBeInTheDocument();
  });
});
```

Notes on the queries:
- **Breadcrumb:** the test finds it with `getByRole('navigation', { name: tr.graph.level })`, so give the `Breadcrumbs` a wrapping `<nav aria-label={tr.graph.level}>`.
- **Level control:** Mantine `SegmentedControl` renders radio inputs labelled by their item labels. If the role or name differs in Mantine 9, use the label query that clicks the MODULE segment, and keep the URL assertion.
- **Shared handlers:** `mocks.handlers` accumulates across tests, so the test takes the latest handler (`.at(-1)`).

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test`
Expected: failures; the screen and route do not exist yet.

- [ ] **Step 3: Implement**

`frontend/src/api/repoGraph.ts` (Task 3 adds the report and export):

```ts
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { GraphQuery } from '../features/graph/graphParams';
import { api, call } from './client';

export function useRepoGraph(id: number, query: GraphQuery) {
  return useQuery({
    queryKey: ['repo-graph', id, query],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/repositories/{id}/graph', {
      params: { path: { id }, query: { level: query.level, focus: query.focus, includeExternal: query.includeExternal } },
    })),
  });
}
```

`frontend/src/features/graph/RepoGraphView.tsx`:

```tsx
import { Stack, Text, useComputedColorScheme } from '@mantine/core';
import cytoscape from 'cytoscape';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import type { components } from '../../api/schema';
import { tr } from '../../i18n/tr';
import { edgeKinds, graphElements } from './graphElements';

type RepoGraph = components['schemas']['RepoGraph'];
type GraphEdge = components['schemas']['GraphEdge'];

/** Presentational styling: shape by node type, colour and size from the element data, readable in both schemes. */
function graphStyle(dark: boolean): cytoscape.StylesheetJson {
  return [
    { selector: 'node', style: {
      label: 'data(label)', 'font-size': 10, 'background-color': 'data(color)', width: 'data(size)', height: 'data(size)',
      color: dark ? '#f1f3f5' : '#212529', 'text-outline-width': 2, 'text-outline-color': dark ? '#1a1b1e' : '#ffffff',
    } },
    { selector: 'node[type = "MODULE"]', style: { shape: 'round-rectangle' } },
    { selector: 'node[type = "PACKAGE"]', style: { shape: 'barrel' } },
    { selector: 'node[type = "METHOD"], node[type = "CONSTRUCTOR"]', style: { shape: 'round-diamond' } },
    { selector: 'node[type = "FIELD"]', style: { shape: 'round-triangle' } },
    { selector: 'node[type = "EXTERNAL_REPOSITORY"], node[type = "EXTERNAL_LIBRARY"]', style: { shape: 'hexagon' } },
    { selector: 'node[?entry]', style: { 'border-width': 3, 'border-color': '#e03131' } },
    { selector: 'edge', style: {
      width: 'data(width)', 'line-color': '#adb5bd', 'target-arrow-shape': 'triangle', 'target-arrow-color': '#adb5bd',
      'curve-style': 'bezier',
    } },
  ];
}

/** The repository graph (cose layout); a node tap is handed to the page, an edge tap shows its usage kinds. */
export default function RepoGraphView({ graph, onNode }: { graph: RepoGraph; onNode: (id: string) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const dark = useComputedColorScheme('light') === 'dark';
  const [edge, setEdge] = useState<GraphEdge | null>(null);
  const tapNode = useEffectEvent((id: string) => onNode(id));

  useEffect(() => {
    if (!container.current) {
      return;
    }
    const edges = new Map((graph.edges ?? []).map((e) => [`${e.from}->${e.to}`, e]));
    const view = cytoscape({
      container: container.current,
      elements: graphElements(graph),
      style: graphStyle(dark),
      layout: { name: 'cose', animate: false },
    });
    view.on('tap', 'node', (event) => tapNode(event.target.id()));
    view.on('tap', 'edge', (event) => setEdge(edges.get(event.target.id()) ?? null));
    return () => view.destroy();
  }, [graph, dark]);

  const labelOf = (id: string | undefined) => graph.nodes?.find((node) => node.id === id)?.label ?? id ?? tr.common.none;
  return (
    <Stack gap="xs">
      <div ref={container} data-testid="repo-graph" style={{ height: 560, width: '100%' }} />
      <Text size="sm" c="dimmed">
        {edge ? tr.graph.edgeInfo(labelOf(edge.from), labelOf(edge.to), edgeKinds(edge)) : tr.graph.tapHint}
      </Text>
    </Stack>
  );
}
```

If `useEffectEvent` is not exported by the installed React or is rejected by the lint rules, keep the latest `onNode` in a ref updated in a separate `useEffect`, and call `ref.current(id)` from the tap handler. The graph must not be rebuilt when only `onNode`'s identity changes. Report which variant you used. `onNode` changes on every parent render, so it must stay out of the effect's dependencies either way.

`frontend/src/features/graph/GraphBreadcrumb.tsx`:

```tsx
import { Anchor, Breadcrumbs, Text } from '@mantine/core';
import { Link } from 'react-router';
import { tr } from '../../i18n/tr';
import { breadcrumb, graphHref, type GraphLevel } from './graphParams';

export function GraphBreadcrumb({ id, level, focus, includeExternal }: {
  id: number;
  level: GraphLevel | undefined;
  focus: string | undefined;
  includeExternal: boolean;
}) {
  const crumbs = breadcrumb(level, focus);
  return (
    <nav aria-label={tr.graph.level}>
      <Breadcrumbs>
        {crumbs.map((crumb, index) =>
          index === crumbs.length - 1 ? (
            <Text key={index} fw={600}>{crumb.label}</Text>
          ) : (
            <Anchor key={index} component={Link}
              to={graphHref(id, { level: crumb.level, focus: crumb.focus, includeExternal })}>
              {crumb.label}
            </Anchor>
          ),
        )}
      </Breadcrumbs>
    </nav>
  );
}
```

The test expects the packages crumb to link to `/repositories/3/graph?level=PACKAGE`. That holds because `includeExternal` is false in that test; with it on, the link carries `&includeExternal=true`.

`frontend/src/features/graph/GraphToolbar.tsx`:

```tsx
import { Button, Group, SegmentedControl, Switch } from '@mantine/core';
import { tr } from '../../i18n/tr';
import { LEVELS, type GraphLevel } from './graphParams';

/** Level, external nodes and export. METHOD is reached by drilling into a class, so it is not offered here. */
export function GraphToolbar({ level, shownLevel, includeExternal, onLevel, onExternal, onExport, exporting }: {
  level: GraphLevel | undefined;
  shownLevel: GraphLevel | undefined;
  includeExternal: boolean;
  onLevel: (level: GraphLevel) => void;
  onExternal: (on: boolean) => void;
  onExport?: (format: 'graphml' | 'json') => void;
  exporting?: boolean;
}) {
  const current = level ?? shownLevel;
  return (
    <Group justify="space-between">
      <Group>
        <SegmentedControl
          aria-label={tr.graph.level}
          value={current ?? ''}
          onChange={(value) => onLevel(value as GraphLevel)}
          data={LEVELS.map((value) => ({
            value,
            label: tr.enums.graphLevel[value],
            disabled: value === 'METHOD' && current !== 'METHOD',
          }))}
        />
        <Switch label={tr.graph.includeExternal} checked={includeExternal}
          onChange={(event) => onExternal(event.currentTarget.checked)} />
      </Group>
      <Group>
        <Button variant="light" disabled={!onExport} loading={exporting} onClick={() => onExport?.('graphml')}>
          {tr.graph.export.graphml}
        </Button>
        <Button variant="light" disabled={!onExport} loading={exporting} onClick={() => onExport?.('json')}>
          {tr.graph.export.json}
        </Button>
      </Group>
    </Group>
  );
}
```

`frontend/src/features/graph/RepoGraphPage.tsx`:

```tsx
import { Alert, Grid, Stack, Text, Title } from '@mantine/core';
import { lazy, Suspense } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useRepository } from '../../api/repositories';
import { useRepoGraph } from '../../api/repoGraph';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { useUrlState } from '../../hooks/useUrlState';
import { enumLabel } from '../../i18n/enumLabel';
import { formatRepository } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { NotFoundPage } from '../../pages/NotFoundPage';
import { GraphBreadcrumb } from './GraphBreadcrumb';
import { GraphToolbar } from './GraphToolbar';
import { drillDown, graphParamsFrom, graphQueryFrom, levelChange } from './graphParams';

/** Cytoscape stays out of the main chunk, as for the impact graph. */
const RepoGraphView = lazy(() => import('./RepoGraphView'));

export function RepoGraphPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <NotFoundPage />;
  }
  return <RepoGraphScreen id={id} />;
}

function RepoGraphScreen({ id }: { id: number }) {
  const { params, update } = useUrlState();
  const navigate = useNavigate();
  const query = graphQueryFrom(params);
  const repository = useRepository(id);
  const graph = useRepoGraph(id, query);
  const shown = graph.data;

  function onNode(nodeId: string) {
    const node = shown?.nodes?.find((candidate) => candidate.id === nodeId);
    const target = node ? drillDown(node) : null;
    if (target?.kind === 'graph') {
      update(graphParamsFrom({ level: target.level, focus: target.focus, includeExternal: query.includeExternal }));
    } else if (target?.kind === 'search') {
      navigate(`/?q=${encodeURIComponent(target.q)}`);
    }
  }

  return (
    <Stack gap="md">
      <Title order={2}>{tr.graph.title(formatRepository(repository.data?.repository))}</Title>
      <GraphToolbar
        level={query.level}
        shownLevel={shown?.level}
        includeExternal={query.includeExternal}
        onLevel={(level) => update(graphParamsFrom(levelChange(query, level)))}
        onExternal={(on) => update(graphParamsFrom({ ...query, includeExternal: on }))}
      />
      <Grid>
        <Grid.Col span={{ base: 12, lg: 8 }}>
          {shown ? (
            <Stack gap="sm" style={{ opacity: graph.isPlaceholderData ? 0.5 : 1 }}>
              <GraphBreadcrumb id={id} level={shown.level} focus={shown.focus} includeExternal={query.includeExternal} />
              {shown.truncated && (
                <Alert color="orange" title={tr.graph.truncated(enumLabel(tr.enums.graphLevel, shown.level))}>
                  {shown.suggestion}
                </Alert>
              )}
              {shown.nodes?.length ? (
                <Suspense fallback={<Loading />}>
                  <RepoGraphView graph={shown} onNode={onNode} />
                </Suspense>
              ) : (
                <Text>{tr.graph.empty}</Text>
              )}
            </Stack>
          ) : graph.isError ? (
            <ErrorView error={graph.error} onRetry={() => void graph.refetch()} />
          ) : (
            <Loading />
          )}
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}>{/* Task 3: <RepoGraphReportPanel id={id} query={query} /> */}</Grid.Col>
      </Grid>
    </Stack>
  );
}
```

Do not leave that comment in place after Task 3. In Task 2 the grid column may stay empty, or you can render the report panel once it exists.

In `frontend/src/routes.tsx`, add `{ path: 'repositories/:id/graph', element: <RepoGraphPage /> }` next to the repository routes.

In `RepositoryPage.tsx`'s header group, add:

```tsx
<Button component={Link} to={`/repositories/${id}/graph`} variant="light">{tr.graph.show}</Button>
```

Import `Button`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass.

Confirm in the build output that the graph code shares the lazy chunk with Cytoscape and is not in the main chunk. List `dist/assets` and their sizes in the report.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): repository graph screen with levels, drill-down, breadcrumb and rollup notice" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 3: Report panel and export

**Files:**
- Create: `frontend/src/features/graph/RepoGraphReportPanel.tsx`
- Modify: `frontend/src/api/repoGraph.ts`, `frontend/src/features/graph/RepoGraphPage.tsx`, `README.md`
- Test: `frontend/src/features/graph/repoGraphReport.test.tsx`

**Interfaces:**
- **Consumes:** `GET /api/v1/repositories/{id}/graph/report` → `RepoGraphReport`; `GET …/graph/export?format&level&focus&includeExternal` → file; `saveBlob`, `filenameOf`, `isBlob`, `notifyError`; Task 1's `graphHref` and `packageOf`.
- **Produces:**
  - `useRepoGraphReport(id)`.
  - `useExportRepoGraph(id)`: a mutation. Its variables are `{ format: 'graphml' | 'json'; query: GraphQuery }`; on error it calls `notifyError`, and a 401 signs the user out through the global mutation cache. The saved name is the server's, or the fallback `tr.graph.exportFileName(level lower-case, format)`.
  - `RepoGraphReportPanel { id }`:
    - **Stale warning:** `tr.graph.report.stale(indexedCommit, analyzedCommit)` when `stale` is true; `tr.graph.report.notAnalyzed` when there is no `analyzedCommit`.
    - **Counts:** shown with `formatNumber`, plus `analyzedAt`.
    - **Critical classes:** in a table. The class name links to `graphHref(id, { level: 'METHOD', focus: fqn })`; a `tr.graph.report.openSymbol` link goes to `/symbols/{symbolId}`. The table also shows dependents, in/out, and an entry badge.
    - **Communities:** a list with `label` and `tr.graph.report.communitySize(formatNumber(size))`.
    - **Package cycles:** each package links to `graphHref(id, { level: 'CLASS', focus: pkg })`, joined with `tr.graph.report.cycleSeparator`, or `tr.graph.report.noCycles`.
    - **Entry point classes:** a list where each links to METHOD focus.
  - The page's toolbar export buttons call the mutation with the URL's query.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/graph/repoGraphReport.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

vi.mock('cytoscape', () => ({ default: vi.fn(() => ({ on: vi.fn(), destroy: vi.fn() })) }));

const report = {
  repositoryId: 3, indexedCommit: 'bbb222', analyzedCommit: 'aaa111', analyzedAt: '2026-10-07T09:00:00Z', stale: true,
  moduleCount: 2, packageCount: 4, classCount: 7, dependencyCount: 9, communityCount: 2, entryPointCount: 1,
  criticalClasses: [{ symbolId: 41, fqn: 'com.g.a.AlphaHelper', inDegree: 2, outDegree: 0, dependents: 5, entryPoint: false }],
  communities: [{ id: 1, label: 'com.g.a', size: 4 }],
  cycles: [{ id: 1, packages: ['com.g.a', 'com.g.b'] }],
  entryPointClasses: ['com.g.web.Api'],
};

function backend(options: { exports?: URL[]; exportStatus?: number } = {}) {
  signedIn();
  server.use(
    http.get(apiUrl('/api/v1/repositories/3'), () => HttpResponse.json({ repository: { id: 3, projectKey: 'SHOP', slug: 'api' }, modules: [] })),
    http.get(apiUrl('/api/v1/repositories/3/graph'), () => HttpResponse.json({
      repositoryId: 3, requestedLevel: 'CLASS', level: 'CLASS', focus: 'com.g', truncated: false,
      nodes: [{ id: 'class:com.g.a.Alpha', label: 'com.g.a.Alpha', type: 'CLASS', size: 1 }], edges: [],
    })),
    http.get(apiUrl('/api/v1/repositories/3/graph/report'), () => HttpResponse.json(report)),
    http.get(apiUrl('/api/v1/repositories/3/graph/export'), ({ request }) => {
      options.exports?.push(new URL(request.url));
      if (options.exportStatus) {
        return HttpResponse.json({ status: options.exportStatus, detail: 'format must be graphml or json' }, { status: options.exportStatus });
      }
      return new HttpResponse('<graphml/>', { headers: {
        'Content-Type': 'application/xml', 'Content-Disposition': 'attachment; filename="repository-3-class.graphml"',
      } });
    }),
  );
}

afterEach(() => vi.restoreAllMocks());

describe('repository graph report', () => {
  it('the report shows counts, links into the graph and warns when stale', async () => {
    backend();
    renderApp('/repositories/3/graph?level=CLASS&focus=com.g');

    const panel = await screen.findByRole('region', { name: tr.graph.report.title });
    expect(await within(panel).findByText(tr.graph.report.stale('bbb222', 'aaa111'))).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: 'com.g.a.AlphaHelper' }))
      .toHaveAttribute('href', '/repositories/3/graph?level=METHOD&focus=com.g.a.AlphaHelper');
    expect(within(panel).getByRole('link', { name: tr.graph.report.openSymbol })).toHaveAttribute('href', '/symbols/41');
    expect(within(panel).getByRole('link', { name: 'com.g.b' }))
      .toHaveAttribute('href', '/repositories/3/graph?level=CLASS&focus=com.g.b');
    expect(within(panel).getByRole('link', { name: 'com.g.web.Api' }))
      .toHaveAttribute('href', '/repositories/3/graph?level=METHOD&focus=com.g.web.Api');
    expect(within(panel).getByText(tr.graph.report.communitySize('4'))).toBeInTheDocument();
  });

  it('exports the graph the URL describes', async () => {
    const exports: URL[] = [];
    backend({ exports });
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    renderApp('/repositories/3/graph?level=CLASS&focus=com.g&includeExternal=true');

    await userEvent.click(await screen.findByRole('button', { name: tr.graph.export.graphml }));

    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    const sent = exports.at(-1)!.searchParams;
    expect([sent.get('format'), sent.get('level'), sent.get('focus'), sent.get('includeExternal')])
      .toEqual(['graphml', 'CLASS', 'com.g', 'true']);
  });

  it('a rejected export shows the backend message', async () => {
    backend({ exportStatus: 400 });
    renderApp('/repositories/3/graph?level=CLASS');

    await userEvent.click(await screen.findByRole('button', { name: tr.graph.export.json }));

    expect(await screen.findByText('format must be graphml or json')).toBeInTheDocument();
  });
});
```

Notes:
- **Panel region:** the panel is a `section` with `aria-label={tr.graph.report.title}`.
- **Link names:** a link's accessible name must be exactly the text asserted. Put the entry badge outside the link.

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test`
Expected: failures; the panel and export do not exist yet.

- [ ] **Step 3: Implement**

Add to `frontend/src/api/repoGraph.ts`:

```ts
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { ApiError, api, call, type Problem } from './client';
import { filenameOf, isBlob, saveBlob } from './download';
import { notifyError } from './notify';

export function useRepoGraphReport(id: number) {
  return useQuery({
    queryKey: ['repo-graph-report', id],
    queryFn: () => call(api.GET('/api/v1/repositories/{id}/graph/report', { params: { path: { id } } })),
  });
}

export type GraphExportFormat = 'graphml' | 'json';

async function exportRepoGraph(id: number, format: GraphExportFormat, query: GraphQuery): Promise<void> {
  const { data, error, response } = await api.GET('/api/v1/repositories/{id}/graph/export', {
    params: { path: { id }, query: { format, level: query.level, focus: query.focus, includeExternal: query.includeExternal } },
    parseAs: 'blob',
  });
  if (!response.ok || !isBlob(data)) {
    throw new ApiError(response.status, (typeof error === 'object' && error !== null ? error : {}) as Problem);
  }
  const level = (query.level ?? '').toLowerCase();
  saveBlob(data, filenameOf(response.headers.get('Content-Disposition')) ?? tr.graph.exportFileName(level, format));
}

/** GraphML/JSON of the graph the URL describes; a mutation, so a 401 signs the user out like any other call. */
export function useExportRepoGraph(id: number) {
  return useMutation({
    mutationFn: ({ format, query }: { format: GraphExportFormat; query: GraphQuery }) => exportRepoGraph(id, format, query),
    onError: notifyError,
  });
}
```

Keep `useRepoGraph` from Task 2 and merge the imports.

`frontend/src/features/graph/RepoGraphReportPanel.tsx`:
- A `section` with `aria-label={tr.graph.report.title}` and a `Title order={3}`. It renders data first, then `ErrorView`, then `Loading`.
- **Warnings:**
  - when `analyzedCommit` is missing, an `Alert` with `tr.graph.report.notAnalyzed`;
  - when `stale` is true, an orange `Alert` with `tr.graph.report.stale(indexedCommit ?? none, analyzedCommit ?? none)`.
- **Counts:** a `SimpleGrid` of the six counts with `tr.graph.report.counts` labels and `formatNumber`, then `tr.graph.report.analyzedAt` with `formatDateTime(analyzedAt)`.
- **Critical classes:** a `Table`.
  - The first column holds an `Anchor component={Link}` to `graphHref(id, { level: 'METHOD', focus: fqn, includeExternal: false })` whose text is exactly the fqn.
  - Next to it, an `Anchor component={Link}` to `/symbols/{symbolId}` whose text is exactly `tr.graph.report.openSymbol`. Render it only when `symbolId` exists.
  - The other columns show the dependents (`formatNumber`), `in / out`, and a `Badge` `tr.graph.report.entryPoint` when `entryPoint` is true. The badge sits outside the links.
- **Communities:** a list of `label` with `tr.graph.report.communitySize(formatNumber(size))`.
- **Cycles:** each cycle's packages are `Anchor`s to `graphHref(id, { level: 'CLASS', focus: pkg, includeExternal: false })`, separated by `tr.graph.report.cycleSeparator`. With no cycles, show `tr.graph.report.noCycles`.
- **Entry points:** `entryPointClasses` as `Anchor`s to METHOD focus, with `tr.graph.report.entryPoints` as the heading.

Report links use `includeExternal: false` on purpose: they start a fresh view of the chosen element.

In `RepoGraphPage.tsx`:
- render `<RepoGraphReportPanel id={id} />` in the right column;
- wire the toolbar: `const exporter = useExportRepoGraph(id)`, then pass `onExport={(format) => exporter.mutate({ format, query })}` and `exporting={exporter.isPending}`;
- remove the Task 2 placeholder comment.

`README.md`, in the Web UI "Screens" list:
- add a repository graph line: levels, drill-down by tapping, external nodes, rollup notice, report, GraphML/JSON export, and the `/repositories/{id}/graph?level=&focus=&includeExternal=` link format;
- change the Plan 11 note to "done".

- [ ] **Step 4: Run the tests and the build**

Run (in `frontend/`): `npm test && npm run build`
Expected: all pass.

Run from the repository root: `./mvnw -q package -DskipTests -Dfrontend.node.downloadRoot=https://nodejs.org/dist/ -Dfrontend.npm.registry=https://registry.npmjs.org`
Expected: the WAR builds.

**Optional browser check on WildFly, if a browser automation tool is available:**
- A test WildFly on port 9080 (context `/graphify`, installed under `/tmp/wildfly-gate/wildfly-41.0.0.Final`) and the Oracle container `graphify-wildfly-db` on port 1522 may already be running for the user to try the UI. **Do not stop, restart or redeploy them**, and never touch the user's own WildFly on 8080 or the container `fw-batch-oracle`.
- If port 9080 or 1522 is in use, for example by the user's test instance, skip the check and say so. Do not pick other ports either: the check is optional.
- Otherwise, start your own instance the Plan 9 way (Oracle 1522, offset 1000):
  - sign in;
  - open `/graphify/repositories/1/graph` (with an empty index, expect the not-found or empty view);
  - confirm the console has no CSP violations and that the Cytoscape chunk loads only on the graph page;
  - stop that instance and remove its deployment and container.

Report what you checked.

- [ ] **Step 5: Commit**

```bash
git add frontend/src README.md
git commit -m "feat(frontend): repository graph report panel and GraphML/JSON export" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 4: English software terms everywhere

**Origin:** the user checked the running UI (2026-10-07). Translated software terms ("Sınıf", "Metot") read wrong to developers, because "class" is what they write in code.

The user also asked about the kind select, and confirmed it works: its clear button (×) brings back "all kinds". There is no select change in this task.

**Files:**
- Modify: `frontend/src/i18n/tr.ts`
- Modify: every component or test that asserts on a changed text through `tr`. These follow automatically; fix only literal expectations, if any exist.
- Test: `frontend/src/i18n/terms.test.ts`

**Interfaces:**
- **Consumes:** `tr`.
- **Produces:**
  - The `tr` values listed below.

- [ ] **Step 1: Write the failing test**

`frontend/src/i18n/terms.test.ts`:

```ts
import { expect, it } from 'vitest';
import { tr } from './tr';

/** Software terms stay in English (user ruling); this guards against translating them back. */
// \b is ASCII-only in JavaScript, so Turkish letters need Unicode-aware boundaries
const TRANSLATED = /(?<!\p{L})(sınıf|metot|metod|yapıcı|arayüz|alan okuma|alan yazma|paket|modül|kütüphane|bağımlılık)/iu;

function texts(value: unknown, path = 'tr'): [string, string][] {
  if (typeof value === 'string') {
    return [[path, value]];
  }
  if (typeof value === 'function') {
    return [[path, String((value as (...args: unknown[]) => unknown)(...['1', '2', '3']))]];
  }
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, child]) => texts(child, `${path}.${key}`));
  }
  return [];
}

it('keeps software terms in English in every UI text', () => {
  const offending = texts(tr).filter(([, text]) => TRANSLATED.test(text));
  expect(offending).toEqual([]);
});

it('labels the Java element kinds as developers write them', () => {
  expect(tr.enums.symbolKind).toMatchObject({
    CLASS: 'Class', INTERFACE: 'Interface', ENUM: 'Enum', RECORD: 'Record', ANNOTATION_TYPE: 'Annotation',
    METHOD: 'Method', CONSTRUCTOR: 'Constructor', FIELD: 'Field',
  });
});
```

The function-value case passes placeholder numbers, so templated texts such as `level(n)` are checked too. If a function needs typed arguments, call it with values of the right type.

- [ ] **Step 2: Run it to verify they fail**

Run: `npm test` (in `frontend/`)
Expected: the terms test fails on the current Turkish labels.

- [ ] **Step 3: Implement**

Text changes in `tr.ts` (keep every other text):

| Key | New value |
|---|---|
| `enums.symbolKind` | `CLASS: 'Class', INTERFACE: 'Interface', ENUM: 'Enum', RECORD: 'Record', ANNOTATION_TYPE: 'Annotation', METHOD: 'Method', CONSTRUCTOR: 'Constructor', FIELD: 'Field'` |
| `enums.usageKind` | `CALL: 'Method çağrısı', INSTANTIATION: 'Instantiation (new)', METHOD_REF: 'Method reference', TYPE_REF: 'Type reference', EXTENDS: 'extends', IMPLEMENTS: 'implements', OVERRIDES: 'override', FIELD_READ: 'Field okuma', FIELD_WRITE: 'Field yazma', ANNOTATION: 'Annotation'` |
| `enums.origin` | `SOURCE: 'Kaynak kod', BINARY: 'Jar (binary)'` |
| `enums.nodeType` / `enums.graphLevel` (Task 1) | English names as in Task 1 |
| `search.query` | `'Class, method veya field'` |
| `search.hint` | unchanged examples |
| `symbol.parent` | `'Ait olduğu class'` |
| `symbol.supertypes` / `subtypes` | `'Üst tipler (extends/implements)'` / `'Alt tipler'` |
| `symbol.overrides` / `overriddenBy` | `'Override ettiği method'` / `'Override eden method'` |
| `symbol.members` | `'Üyeler (method, field)'` |
| `symbol.columns.module` | `'Module'` |
| `impact.summary` | `repositories: 'Repository', modules: 'Module', classes: 'Class', methods: 'Method', usages: 'Kullanım'` |
| `impact.dispatch` | `'Dinamik bağlanmayı (override / interface implementasyonu) dahil et'` |
| `impact.entryColumns.symbol` | `'Method'` |
| `impact.versionColumns.dependency` | `'Dependency'` |
| `repositories.modules` / `moduleColumns` | `"Module'ler"` (a double-quoted string, because of the apostrophe) / `path: 'Path', coordinates: 'Maven koordinatı', classpath: 'Classpath'` |
| Any other value that contains a word the terms test rejects | rephrase with the English term inside a Turkish sentence |

Also apply the rule to every new text Tasks 1–3 add. The terms test enforces it.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "fix(frontend): keep software terms in English" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```
