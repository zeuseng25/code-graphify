# Plan 10: User Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every signed-in user the screens that answer the product's questions: "where is X used, in how many projects, and who is affected if it changes". This covers symbol search, symbol detail (declarations, hierarchy, usage summary, paged usages), impact analysis (result sections, entry points, warnings, a small graph and CSV export), repositories with their run history, and index runs with live progress.

**Architecture:**
- **Data access:** one query hook per endpoint in `src/api/*.ts`, built on the generated `openapi-fetch` client and TanStack Query.
- **Screen state:** filters, page, impact inputs and so on live in URL search parameters through a small `useUrlState` hook. Every screen is linkable, and refreshing a page keeps its state.
- **Shared building blocks:** a pager, enum badges with Turkish labels, a date formatter, and a blob download helper.
- **Impact graph:** drawn with Cytoscape (`breadthfirst` by level, seeds as roots). It is shown only when the node count is within `graphMaxNodes` from `/ui-config`.
- **Live run progress:** a running index run refreshes every `pollIntervalMillis` (also from `/ui-config`).

**Tech Stack:** React 19, TypeScript 5.9, Mantine 9, React Router 8, TanStack Query 5, openapi-fetch, Cytoscape.js (new dependency), Vitest + Testing Library + MSW.

**Spec:** `docs/superpowers/specs/2026-10-06-web-ui-design.md` §1.2 (criteria 1, 4, 6), §4.1 rows 3–6 and 8, §6, §7. Backend API: `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` §10.3–10.5.

**Rulings taken while planning:**
- **Search runs on submit (Enter or the button), not while typing.** A debounce would need a timing constant, and the user's rule forbids operational constants in code.
- **No route ends in a segment containing `.`.** This is a Plan 9 carry-over: the SPA forward filter treats a dotted last segment as a file. Symbols, repositories and runs are addressed by numeric id. Class names travel only in query strings.
- **Search's repository filter:** a searchable select over the first page of `/repositories`, fetched with `size = pageMaxSize` from `/ui-config`.
- **Usages' repository filter:** offers exactly the repositories in that symbol's usage summary.
- **Impact inputs live in the URL** (`?symbol=1&symbol=2&changeType=&depth=&confidence=&dispatch=`).
  - Opening such a link runs the analysis; the impact request is a POST, but it is idempotent, so it is safe to run as a query.
  - The form edits a draft, and "Analiz et" writes the draft to the URL.
- **Page size is not sent.** The backend applies `api.page_default_size` when `size` is absent. Pagination uses the `size` and `total` the backend returns.
- **Client-side paging uses `pageDefaultSize` from `/ui-config`.** It applies only to lists that arrive whole (an impact level's usage locations).
- **The search page becomes the home page.** `HomePage` and `tr.home` are removed. The Plan 9 auth tests that waited for `tr.home.title` now wait for `tr.search.title`.
- **The "Grafı göster" button on the repository detail is added by Plan 11.** Plan 10 does not link to a page that does not exist yet.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–8 | Backend | merged |
| 9 | Web foundation (WAR on WildFly, SPA serving, auth screens, layout) | merged |
| **10** | **User screens: search, symbol detail, impact, repositories, runs** (this plan) | — |
| 11 | Repo graph screen | next |
| 12 | Admin screens | — |

## Global Constraints

- **Environment:**
  - Frontend commands run in `frontend/` with Node 24 and npm 11.
  - `npm test` runs `check:api`, the typecheck, lint and Vitest; `npm run build` must pass.
  - Backend commands (only if touched) need `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; use `./mvnw`.
- **No new dependency except `cytoscape`** (Task 3). It ships its own TypeScript types; do not add `@types/cytoscape` unless typecheck needs it.
- **"Kodda sabit değer yok" (no hardcoded values):**
  - No page size, depth, node limit, poll interval or URL appears in code. They come from the backend's paging, or from `useUiConfig()` (`pageDefaultSize`, `pageMaxSize`, `graphMaxNodes`, `impactDefaultDepth`, `impactMaxDepth`, `pollIntervalMillis`).
  - Allowed exceptions: presentational styling (sizes, colours, gaps), protocol facts (the `csv` format name, the `tr-TR` locale, the `Content-Disposition` grammar) and enum names from the API.
- **Text:**
  - Every user-visible text lives in `src/i18n/tr.ts`, in Turkish. Enum values are shown through `tr.enums` labels.
  - Backend problem details are shown through `errorMessage()`.
  - A missing value shows `tr.common.none`.
- **Errors:**
  - Failed loads render `ErrorView`.
  - Failed actions (CSV export) show a Mantine notification with `errorMessage(error)`.
  - 401 is already handled globally (Plan 9).
- **Types:**
  - API shapes come only from `src/api/schema.d.ts` (`components['schemas'][…]`); never redeclare them.
  - Response fields are optional in the generated types; handle `undefined` without inventing values.
- **Routes:**
  - Numeric ids only in paths; no dotted last segment.
  - Screen state lives in URL search params.
  - An invalid `:id` renders `NotFoundPage`.
- **Tests:**
  - MSW stands in for the backend; `onUnhandledRequest: 'error'` stays on.
  - Use `signedIn()` from `src/test/backend.ts` (Task 1) and `renderApp(path)`.
  - Assert on Turkish texts through `tr`, never on hardcoded strings, except backend-provided data.
- **The user's uncommitted root `.gitignore` change must never be committed.** Stage `frontend/...` paths explicitly.
- **Commits** end with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V
  ```

## Review Focus

1. **A method with overloads.** The search shows each overload on its own row, with its signature, usage count and repository count; picking one opens that overload, not its siblings. Test: Task 2 `search.test.tsx` "lists overloads separately and opens the chosen one".
2. **An impact link someone shares** (`/impact?symbol=7&depth=2&confidence=EXACT`). Opening it re-runs the same analysis with the same inputs, and the CSV export sends exactly that request. Test: Task 3 `impact.test.tsx` "runs the analysis from the link and exports the same request".
3. **A large impact result.** The page stays usable: the graph is replaced by a notice above `graphMaxNodes`, usage locations are paged by `pageDefaultSize`, and `truncated` is announced. Test: Task 3 `impact.test.tsx` "a result over the node limit shows no graph and pages its locations".
4. **A run that is still indexing.** Its page refreshes every `pollIntervalMillis` and stops refreshing once it has finished. Test: Task 4 `runs.test.tsx` "a running run refreshes until it finishes".
5. **Backend errors on these screens.** A 400 on a bad search or impact request shows the backend's detail. An unknown symbol id shows the not-found or error view, never a blank page. Tests: Task 2 "an unknown symbol shows the backend's message"; Task 3 "shows the backend's message for a rejected request".

---

## File Structure (all under `frontend/src/`)

| File | Responsibility |
|---|---|
| `i18n/tr.ts` (modify), `i18n/format.ts`, `i18n/enumLabel.ts` | Texts, Turkish enum labels, date and number formatting |
| `hooks/useUrlState.ts` | Read and update URL search params (resets the page on filter changes) |
| `components/Pager.tsx`, `components/Badges.tsx`, `components/Loading.tsx`, `components/SymbolLink.tsx` | Shared UI parts |
| `api/download.ts`, `api/notify.ts` | Blob download with the Content-Disposition name; error notifications |
| `api/symbols.ts`, `api/impact.ts`, `api/repositories.ts`, `api/runs.ts` | Query hooks and actions per endpoint |
| `features/search/SearchPage.tsx`, `features/search/RepositorySelect.tsx` | Symbol search (home page) |
| `features/symbol/SymbolPage.tsx`, `UsageSummaryView.tsx`, `UsagesTable.tsx` | Symbol detail |
| `features/impact/ImpactPage.tsx`, `ImpactForm.tsx`, `ImpactResultView.tsx`, `ImpactGraphView.tsx`, `impactElements.ts`, `impactParams.ts` | Impact analysis |
| `features/repositories/RepositoriesPage.tsx`, `RepositoryPage.tsx` | Repository list and detail |
| `features/runs/RunsPage.tsx`, `RunPage.tsx`, `RunRepositoriesTable.tsx` | Index runs |
| `routes.tsx`, `layout/navigation.ts`, `layout/AppLayout.tsx` (modify) | Routes and menu |
| `test/backend.ts` | Signed-in backend handlers for tests |

---

### Task 1: Shared building blocks

**Files:**
- Create: `frontend/src/i18n/format.ts`, `frontend/src/i18n/enumLabel.ts`, `frontend/src/hooks/useUrlState.ts`, `frontend/src/components/Pager.tsx`, `frontend/src/components/Badges.tsx`, `frontend/src/components/Loading.tsx`, `frontend/src/components/SymbolLink.tsx`, `frontend/src/api/download.ts`, `frontend/src/api/notify.ts`, `frontend/src/test/backend.ts`
- Modify: `frontend/src/i18n/tr.ts`
- Test: `frontend/src/i18n/format.test.ts`, `frontend/src/hooks/useUrlState.test.tsx`, `frontend/src/components/Pager.test.tsx`, `frontend/src/api/download.test.ts`

**Interfaces:**
- **Consumes:** `components['schemas']` from `api/schema.d.ts`; `errorMessage`; `server` and `apiUrl` from `test/server.ts`; Mantine.
- **Produces:**
  - `tr.enums.{symbolKind, usageKind, confidence, origin, changeType, nodeRole, repoStatus, runStatus, runTrigger, runScope, classpathMode}`, plus `tr.common.{none, empty, search, apply, clear, yes, no}`.
  - `enumLabel(labels, value)`: the Turkish label, the raw value if unknown, or `tr.common.none` when absent.
  - `formatDateTime(iso)` and `formatNumber(n)` (both `tr-TR`).
  - `useUrlState()`: `{ params, page, update(changes, keepPage?) }`. `page` is 0-based and sanitised. `update` sets or removes keys (an array becomes repeated keys) and drops `page` unless `keepPage` is true or `page` is among the changes.
  - `<Pager page size total onChange />` takes a 0-based page and renders nothing when there is a single page.
  - `<ConfidenceBadge/>`, `<UsageKindBadge/>`, `<SymbolKindBadge/>`, `<OriginBadge/>`, `<RepoStatusBadge/>`, `<RunStatusBadge/>`.
  - `<Loading/>` and `<SymbolLink symbol/>`. `SymbolLink` links to `/symbols/{id}` with the display text and shows the key as its `title`.
  - `filenameOf(contentDisposition)` and `saveBlob(blob, name)`.
  - `notifyError(error)`.
  - `signedIn(options?)`: MSW handlers for csrf, me and ui-config. The test ui-config has `pollIntervalMillis: 20`, and the returned objects let tests vary the values.

- [ ] **Step 1: Write the failing tests**

`frontend/src/i18n/format.test.ts`:

```ts
import { expect, it } from 'vitest';
import { tr } from './tr';
import { enumLabel } from './enumLabel';
import { formatDateTime, formatNumber } from './format';

it('labels enum values in Turkish and falls back safely', () => {
  expect(enumLabel(tr.enums.confidence, 'EXACT')).toBe(tr.enums.confidence.EXACT);
  expect(enumLabel(tr.enums.confidence, 'SOMETHING_NEW')).toBe('SOMETHING_NEW');
  expect(enumLabel(tr.enums.confidence, undefined)).toBe(tr.common.none);
});

it('formats dates and numbers for Turkish readers', () => {
  expect(formatNumber(12345)).toBe('12.345');
  expect(formatNumber(undefined)).toBe(tr.common.none);
  expect(formatDateTime('2026-10-07T09:05:00Z')).toMatch(/2026/);
  expect(formatDateTime(undefined)).toBe(tr.common.none);
});
```

`frontend/src/hooks/useUrlState.test.tsx`:

```tsx
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { expect, it } from 'vitest';
import { useUrlState } from './useUrlState';

function setup(path: string) {
  let state: ReturnType<typeof useUrlState> | undefined;
  function Probe() {
    state = useUrlState();
    return null;
  }
  const router = createMemoryRouter([{ path: '*', element: <Probe /> }], { initialEntries: [path] });
  renderHook(() => null, { wrapper: ({ children }: { children: ReactNode }) => <><RouterProvider router={router} />{children}</> });
  return { router, state: () => state! };
}

it('reads the page and resets it when a filter changes', () => {
  const { router, state } = setup('/x?q=foo&page=3&kind=METHOD');
  expect(state().page).toBe(3);

  act(() => state().update({ kind: 'CLASS' }));
  expect(router.state.location.search).toBe('?q=foo&kind=CLASS');

  act(() => state().update({ page: 2 }));
  expect(router.state.location.search).toBe('?q=foo&kind=CLASS&page=2');

  act(() => state().update({ confidence: ['EXACT', 'RECOVERED'], q: null }, true));
  expect(router.state.location.search).toBe('?kind=CLASS&page=2&confidence=EXACT&confidence=RECOVERED');
});

it('treats a bad page parameter as the first page', () => {
  expect(setup('/x?page=-4').state().page).toBe(0);
  expect(setup('/x?page=abc').state().page).toBe(0);
});
```

If `renderHook` with a router wrapper is awkward in this React Router version, render `<RouterProvider router={router} />` with `render()` and read `state()` the same way. The assertions stay as they are.

`frontend/src/components/Pager.test.tsx`:

```tsx
import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { Pager } from './Pager';

it('is hidden for a single page and reports 0-based pages', async () => {
  const onChange = vi.fn();
  const { container, rerender } = render(<MantineProvider><Pager page={0} size={50} total={50} onChange={onChange} /></MantineProvider>);
  expect(container).toBeEmptyDOMElement();

  rerender(<MantineProvider><Pager page={0} size={50} total={120} onChange={onChange} /></MantineProvider>);
  await userEvent.click(screen.getByRole('button', { name: '3' }));
  expect(onChange).toHaveBeenCalledWith(2);
});
```

`MantineProvider` may add elements, making `toBeEmptyDOMElement` fail. In that case, assert that there is no `navigation` or pagination role instead.

`frontend/src/api/download.test.ts`:

```ts
import { afterEach, expect, it, vi } from 'vitest';
import { filenameOf, saveBlob } from './download';

afterEach(() => vi.restoreAllMocks());

it('reads the file name from Content-Disposition', () => {
  expect(filenameOf('attachment; filename="impact-7.csv"')).toBe('impact-7.csv');
  expect(filenameOf('attachment; filename=plain.csv')).toBe('plain.csv');
  expect(filenameOf("attachment; filename*=UTF-8''etki%20raporu.csv")).toBe('etki raporu.csv');
  expect(filenameOf(null)).toBeNull();
});

it('saves a blob through a temporary link', () => {
  URL.createObjectURL = vi.fn(() => 'blob:test');
  URL.revokeObjectURL = vi.fn();
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

  saveBlob(new Blob(['a,b']), 'x.csv');

  expect(click).toHaveBeenCalledOnce();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test');
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test` (in `frontend/`)
Expected: failures; the modules do not exist yet.

- [ ] **Step 3: Implement**

In `frontend/src/i18n/tr.ts`:
- replace `nav.home` with the new menu labels;
- remove `home`;
- add the sections below and keep everything else.

```ts
  nav: { search: 'Sembol arama', impact: 'Etki analizi', repositories: 'Repolar', runs: 'Taramalar', admin: 'Yönetim', menu: 'Menü' },
  common: {
    loading: 'Yükleniyor…',
    none: '—',
    empty: 'Kayıt bulunamadı.',
    search: 'Ara',
    apply: 'Uygula',
    clear: 'Temizle',
    yes: 'Evet',
    no: 'Hayır',
  },
  enums: {
    symbolKind: {
      CLASS: 'Sınıf', INTERFACE: 'Arayüz', ENUM: 'Enum', RECORD: 'Record', ANNOTATION_TYPE: 'Annotation tipi',
      METHOD: 'Metot', CONSTRUCTOR: 'Yapıcı', FIELD: 'Alan',
    },
    usageKind: {
      CALL: 'Çağrı', INSTANTIATION: 'Nesne oluşturma', METHOD_REF: 'Metot referansı', TYPE_REF: 'Tip referansı',
      EXTENDS: 'Kalıtım', IMPLEMENTS: 'Arayüz uygulama', OVERRIDES: 'Override', FIELD_READ: 'Alan okuma',
      FIELD_WRITE: 'Alan yazma', ANNOTATION: 'Annotation',
    },
    confidence: { EXACT: 'Kesin', RECOVERED: 'Kurtarılmış', NAME_ONLY: 'Yalnızca isim' },
    origin: { SOURCE: 'Kaynak kod', BINARY: 'Derlenmiş (jar)' },
    changeType: { SIGNATURE: 'İmza değişikliği', BEHAVIOR: 'Davranış değişikliği' },
    nodeRole: { SEED: 'Değişen', AFFECTED: 'Etkilenen', DISPATCH: 'Dinamik bağlanma', TWIN: 'İkiz' },
    repoStatus: {
      SUCCESS: 'Başarılı', SUCCESS_PARTIAL: 'Kısmen başarılı', FAILED: 'Başarısız', CLONE_FAILED: 'Klonlanamadı',
      SKIPPED_UNCHANGED: 'Değişmedi', SKIPPED_NOT_JAVA: 'Java değil', INTERRUPTED: 'Yarıda kesildi',
    },
    runStatus: { RUNNING: 'Çalışıyor', SUCCESS: 'Başarılı', FAILED: 'Başarısız', CANCELLED: 'İptal edildi', INTERRUPTED: 'Yarıda kesildi' },
    runTrigger: { SCHEDULED: 'Zamanlanmış', MANUAL: 'Elle başlatıldı' },
    runScope: { ALL: 'Tüm repolar', CONNECTION: 'Bağlantı', REPOSITORY: 'Repo' },
    classpathMode: { FULL: 'Tam', PARTIAL: 'Kısmi', NONE: 'Yok' },
  },
```

Remove `home` from `tr`. Tasks 2–4 add their own sections (`search`, `symbol`, `impact`, `repositories`, `runs`). Update `src/auth/auth.test.tsx`, which references `tr.home.title`: until Task 2 lands, assert on `tr.nav.search` (the menu link). Task 2 switches those assertions to `tr.search.title`. Delete `src/pages/HomePage.tsx` in Task 2, not here.

`frontend/src/i18n/enumLabel.ts`:

```ts
import { tr } from './tr';

/** The Turkish label of an API enum value; an unknown value is shown as sent, a missing one as "none". */
export function enumLabel(labels: Readonly<Record<string, string>>, value: string | undefined | null): string {
  if (value == null || value === '') {
    return tr.common.none;
  }
  return labels[value] ?? value;
}
```

`frontend/src/i18n/format.ts`:

```ts
import { tr } from './tr';

/** The UI is Turkish only (web UI spec §2), so dates and numbers follow the Turkish locale. */
const LOCALE = 'tr-TR';

const dateTime = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'medium' });
const number = new Intl.NumberFormat(LOCALE);

export function formatDateTime(iso: string | undefined | null): string {
  if (!iso) {
    return tr.common.none;
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : dateTime.format(date);
}

export function formatNumber(value: number | undefined | null): string {
  return value == null ? tr.common.none : number.format(value);
}
```

`frontend/src/hooks/useUrlState.ts`:

```ts
import { useSearchParams } from 'react-router';

export type UrlValue = string | number | boolean | readonly string[] | null | undefined;

/** Screen state in the URL (web UI spec §4: linkable screens). A filter change returns to the first page. */
export function useUrlState() {
  const [params, setParams] = useSearchParams();
  const raw = Number(params.get('page'));
  const page = Number.isInteger(raw) && raw > 0 ? raw : 0;

  function update(changes: Record<string, UrlValue>, keepPage = false) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      next.delete(key);
      if (value == null || value === '') {
        continue;
      }
      if (Array.isArray(value)) {
        value.forEach((item) => next.append(key, item));
      } else {
        next.set(key, String(value));
      }
    }
    if (!keepPage && !('page' in changes)) {
      next.delete('page');
    }
    if (next.get('page') === '0') {
      next.delete('page');
    }
    setParams(next);
  }

  return { params, page, update };
}
```

`frontend/src/components/Pager.tsx`:

```tsx
import { Group, Pagination } from '@mantine/core';

/** Pages of a backend list; page is 0-based like the API, Mantine's control is 1-based. */
export function Pager({ page, size, total, onChange }: {
  page: number;
  size: number | undefined;
  total: number | undefined;
  onChange: (page: number) => void;
}) {
  const pages = size && total ? Math.ceil(total / size) : 1;
  if (pages <= 1) {
    return null;
  }
  return (
    <Group justify="center">
      <Pagination total={pages} value={page + 1} onChange={(value) => onChange(value - 1)} />
    </Group>
  );
}
```

`frontend/src/components/Badges.tsx`:

```tsx
import { Badge } from '@mantine/core';
import { enumLabel } from '../i18n/enumLabel';
import { tr } from '../i18n/tr';

/** Presentational colours per value; the label always comes from tr.enums. */
const CONFIDENCE_COLOR: Record<string, string> = { EXACT: 'green', RECOVERED: 'yellow', NAME_ONLY: 'orange' };
const REPO_STATUS_COLOR: Record<string, string> = {
  SUCCESS: 'green', SUCCESS_PARTIAL: 'yellow', FAILED: 'red', CLONE_FAILED: 'red', SKIPPED_UNCHANGED: 'gray',
  SKIPPED_NOT_JAVA: 'gray', INTERRUPTED: 'orange',
};
const RUN_STATUS_COLOR: Record<string, string> = {
  RUNNING: 'blue', SUCCESS: 'green', FAILED: 'red', CANCELLED: 'gray', INTERRUPTED: 'orange',
};

export function ConfidenceBadge({ value }: { value?: string }) {
  return <Badge variant="light" color={CONFIDENCE_COLOR[value ?? ''] ?? 'gray'}>{enumLabel(tr.enums.confidence, value)}</Badge>;
}

export function UsageKindBadge({ value }: { value?: string }) {
  return <Badge variant="outline">{enumLabel(tr.enums.usageKind, value)}</Badge>;
}

export function SymbolKindBadge({ value }: { value?: string }) {
  return <Badge variant="default">{enumLabel(tr.enums.symbolKind, value)}</Badge>;
}

export function OriginBadge({ value }: { value?: string }) {
  return <Badge variant="dot" color={value === 'SOURCE' ? 'blue' : 'gray'}>{enumLabel(tr.enums.origin, value)}</Badge>;
}

export function RepoStatusBadge({ value }: { value?: string }) {
  return <Badge color={REPO_STATUS_COLOR[value ?? ''] ?? 'gray'}>{enumLabel(tr.enums.repoStatus, value)}</Badge>;
}

export function RunStatusBadge({ value }: { value?: string }) {
  return <Badge color={RUN_STATUS_COLOR[value ?? ''] ?? 'gray'}>{enumLabel(tr.enums.runStatus, value)}</Badge>;
}
```

`frontend/src/components/Loading.tsx`:

```tsx
import { Center, Loader } from '@mantine/core';
import { tr } from '../i18n/tr';

export function Loading() {
  return (
    <Center p="xl">
      <Loader aria-label={tr.common.loading} />
    </Center>
  );
}
```

`frontend/src/components/SymbolLink.tsx`:

```tsx
import { Anchor } from '@mantine/core';
import { Link } from 'react-router';
import type { components } from '../api/schema';
import { tr } from '../i18n/tr';

type SymbolRefLike = Pick<components['schemas']['SymbolRef'], 'id' | 'key' | 'display'>;

/** A symbol by its display signature, linking to its detail page; the full key is the tooltip. */
export function SymbolLink({ symbol }: { symbol?: SymbolRefLike }) {
  if (!symbol?.id) {
    return <>{symbol?.display ?? symbol?.key ?? tr.common.none}</>;
  }
  return (
    <Anchor component={Link} to={`/symbols/${symbol.id}`} title={symbol.key}>
      {symbol.display ?? symbol.key}
    </Anchor>
  );
}
```

`frontend/src/api/download.ts`:

```ts
/** The file name a response suggests (RFC 6266: filename* wins over filename), or null. */
export function filenameOf(header: string | null): string | null {
  if (!header) {
    return null;
  }
  const extended = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header);
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim());
    } catch {
      // fall through to the plain parameter
    }
  }
  const plain = /filename\s*=\s*("([^"]*)"|[^;]+)/i.exec(header);
  if (!plain) {
    return null;
  }
  return (plain[2] ?? plain[1]).trim();
}

/** Hands a downloaded file to the browser through a temporary object URL. */
export function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
```

`frontend/src/api/notify.ts`:

```ts
import { notifications } from '@mantine/notifications';
import { errorMessage } from './errors';

/** A failed action (not a failed page load): the backend's message as a red notification. */
export function notifyError(error: unknown): void {
  notifications.show({ color: 'red', message: errorMessage(error) });
}
```

`frontend/src/test/backend.ts`:

```ts
import { http, HttpResponse } from 'msw';
import { apiUrl, server } from './server';

export const testUser = {
  username: 'ayse', displayName: 'Ayşe', role: 'USER', source: 'LOCAL', mustChangePassword: false,
};

/** UI settings for tests; a short poll interval keeps polling tests fast. */
export const testUiConfig = {
  pageDefaultSize: 50, pageMaxSize: 500, graphMaxNodes: 500, impactDefaultDepth: 3, impactMaxDepth: 10,
  pollIntervalMillis: 20,
};

/** Registers a signed-in session (csrf, me, ui-config); tests add the screen's own handlers. */
export function signedIn(options: { user?: Partial<typeof testUser>; uiConfig?: Partial<typeof testUiConfig> } = {}) {
  const user = { ...testUser, ...options.user };
  const uiConfig = { ...testUiConfig, ...options.uiConfig };
  server.use(
    http.get(apiUrl('/api/v1/auth/csrf'), () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't' })),
    http.get(apiUrl('/api/v1/auth/me'), () => HttpResponse.json(user)),
    http.get(apiUrl('/api/v1/ui-config'), () => HttpResponse.json(uiConfig)),
  );
  return { user, uiConfig };
}
```

In `frontend/src/layout/navigation.ts`, set:

```ts
export const NAV_ITEMS: NavItem[] = [
  { path: '/', label: tr.nav.search },
  { path: '/impact', label: tr.nav.impact },
  { path: '/repositories', label: tr.nav.repositories },
  { path: '/runs', label: tr.nav.runs },
];
```

In `AppLayout.tsx`, mark a menu item active when the location matches it exactly or lies under it. The root `/` is active only on `/` and `/symbols/...`. Use a small helper `isActive(itemPath, pathname)`, add a unit test case in `navigation.test.ts`, and keep the existing role filter.

Until Tasks 2–4 add their routes, `/impact`, `/repositories` and `/runs` render `NotFoundPage`. That is expected.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass, including the updated Plan 9 auth tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): shared building blocks for the user screens" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 2: Symbol search and symbol detail

**Files:**
- Create: `frontend/src/api/symbols.ts`, `frontend/src/api/repositories.ts` (the list hook only; Task 4 extends it), `frontend/src/features/search/SearchPage.tsx`, `frontend/src/features/search/RepositorySelect.tsx`, `frontend/src/features/symbol/SymbolPage.tsx`, `frontend/src/features/symbol/UsageSummaryView.tsx`, `frontend/src/features/symbol/UsagesTable.tsx`
- Modify: `frontend/src/routes.tsx`, `frontend/src/i18n/tr.ts`, `frontend/src/auth/auth.test.tsx`
- Delete: `frontend/src/pages/HomePage.tsx`
- Test: `frontend/src/features/search/search.test.tsx`, `frontend/src/features/symbol/symbol.test.tsx`

**Interfaces:**
- **Consumes:** Task 1's building blocks, and these endpoints:
  - `GET /api/v1/symbols/search?q&kind&repo&page` → `PageSymbolHit`
  - `GET /api/v1/symbols/{id}` → `SymbolDetail`
  - `GET /api/v1/symbols/{id}/usages/summary` → `UsageSummary`
  - `GET /api/v1/symbols/{id}/usages?confidence*&kind*&repo&page` → `PageUsageView`
  - `GET /api/v1/repositories?size` → `PageRepositorySummary`
- **Produces:**
  - Routes: `/` is `SearchPage` with `?q&kind&repo&page`; `/symbols/:id` is `SymbolPage` with `?confidence*&usageKind*&repo&page` for the usages list.
  - Hooks: `useSymbolSearch`, `useSymbol`, `useUsageSummary` and `useUsages` (`api/symbols.ts`); `useRepositoryOptions()` (`api/repositories.ts`), which reads the first page with `size = pageMaxSize`.
  - The symbol page's "Etki analizi" button navigates to `/impact?symbol={id}`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/search/search.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

const repositories = { items: [{ id: 3, projectKey: 'SHOP', slug: 'api' }], page: 0, size: 500, total: 1 };

function searchBackend(onSearch: (url: URL) => void = () => {}) {
  signedIn();
  server.use(
    http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json(repositories)),
    http.get(apiUrl('/api/v1/symbols/search'), ({ request }) => {
      const url = new URL(request.url);
      onSearch(url);
      if (url.searchParams.get('q') === 'nothing') {
        return HttpResponse.json({ items: [], page: 0, size: 50, total: 0 });
      }
      return HttpResponse.json({
        items: [
          { id: 7, key: 'com.shop.lib.PriceFormatter#format(int)', kind: 'METHOD', display: 'format(int)',
            origin: 'SOURCE', nameOnly: false, usageCount: 12, repositoryCount: 3 },
          { id: 8, key: 'com.shop.lib.PriceFormatter#format(java.lang.String)', kind: 'METHOD',
            display: 'format(String)', origin: 'SOURCE', nameOnly: false, usageCount: 1, repositoryCount: 1 },
        ],
        page: 0, size: 50, total: 2,
      });
    }),
  );
}

describe('symbol search', () => {
  it('searches on submit and keeps the query in the URL', async () => {
    const seen: URL[] = [];
    searchBackend((url) => seen.push(url));
    const router = renderApp('/');

    await userEvent.type(await screen.findByLabelText(tr.search.query), 'PriceFormatter#format');
    expect(seen).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: tr.common.search }));

    expect(await screen.findByText('format(int)')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?q=PriceFormatter%23format');
    expect(seen.at(-1)?.searchParams.get('q')).toBe('PriceFormatter#format');
    expect(seen.at(-1)?.searchParams.has('size')).toBe(false);
  });

  it('lists overloads separately and opens the chosen one', async () => {
    searchBackend();
    const router = renderApp('/?q=format');

    const row = (await screen.findByText('format(String)')).closest('tr')!;
    expect(row).toHaveTextContent('1');
    expect(await screen.findByText('format(int)')).toBeInTheDocument();
    await userEvent.click(screen.getByText('format(String)'));

    await waitFor(() => expect(router.state.location.pathname).toBe('/symbols/8'));
  });

  it('says so when nothing matches', async () => {
    searchBackend();
    renderApp('/?q=nothing');

    expect(await screen.findByText(tr.common.empty)).toBeInTheDocument();
  });

  it('shows the backend message for a rejected query', async () => {
    signedIn();
    server.use(
      http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json(repositories)),
      http.get(apiUrl('/api/v1/symbols/search'), () =>
        HttpResponse.json({ status: 400, detail: 'q is too short' }, { status: 400 })),
    );
    renderApp('/?q=a');

    expect(await screen.findByText('q is too short')).toBeInTheDocument();
  });
});
```

`frontend/src/features/symbol/symbol.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

const repo = { id: 3, projectKey: 'SHOP', slug: 'api' };

function symbolBackend(usageRequests: URL[] = []) {
  signedIn();
  server.use(
    http.get(apiUrl('/api/v1/symbols/7'), () => HttpResponse.json({
      symbol: { id: 7, key: 'com.shop.lib.PriceFormatter#format(int)', kind: 'METHOD', display: 'format(int)' },
      origin: 'SOURCE', nameOnly: false,
      parent: { id: 6, key: 'com.shop.lib.PriceFormatter', kind: 'CLASS', display: 'PriceFormatter' },
      declarations: [{ repository: { id: 1, projectKey: 'SHOP', slug: 'lib' }, modulePath: 'shop-lib',
        filePath: 'src/main/java/com/shop/lib/PriceFormatter.java', line: 12 }],
      members: [], overrides: [], overriddenBy: [], supertypes: [], subtypes: [],
    })),
    http.get(apiUrl('/api/v1/symbols/7/usages/summary'), () => HttpResponse.json({
      usages: 12, repositories: 1,
      byRepository: [{ repository: repo, usages: 12, modules: [{ modulePath: 'shop-api', usages: 12,
        classes: [{ classFqn: 'com.shop.api.CheckoutService', usages: 12 }] }] }],
    })),
    http.get(apiUrl('/api/v1/symbols/7/usages'), ({ request }) => {
      usageRequests.push(new URL(request.url));
      return HttpResponse.json({
        items: [{ id: 100, from: { id: 9, key: 'com.shop.api.CheckoutService#total()', kind: 'METHOD', display: 'total()' },
          kind: 'CALL', confidence: 'EXACT', repository: repo, modulePath: 'shop-api',
          filePath: 'src/main/java/com/shop/api/CheckoutService.java', line: 31, column: 9,
          snippet: 'formatter.format(sum)' }],
        page: 0, size: 50, total: 1,
      });
    }),
  );
}

describe('symbol detail', () => {
  it('shows where the symbol is declared, how much it is used and each usage', async () => {
    symbolBackend();
    renderApp('/symbols/7');

    expect(await screen.findByRole('heading', { name: 'format(int)' })).toBeInTheDocument();
    expect(screen.getByText('src/main/java/com/shop/lib/PriceFormatter.java:12')).toBeInTheDocument();
    expect(await screen.findByText('SHOP/api')).toBeInTheDocument();
    expect(await screen.findByText('formatter.format(sum)')).toBeInTheDocument();
    expect(screen.getByText(tr.enums.confidence.EXACT)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'total()' })).toHaveAttribute('href', '/symbols/9');
  });

  it('filters usages by confidence through the URL and the API', async () => {
    const requests: URL[] = [];
    symbolBackend(requests);
    const router = renderApp('/symbols/7?confidence=EXACT&usageKind=CALL');

    await screen.findByText('formatter.format(sum)');
    expect(router.state.location.search).toContain('confidence=EXACT');
    await waitFor(() => expect(requests.at(-1)?.searchParams.getAll('confidence')).toEqual(['EXACT']));
    expect(requests.at(-1)?.searchParams.getAll('kind')).toEqual(['CALL']);
  });

  it('starts an impact analysis for this symbol', async () => {
    symbolBackend();
    const router = renderApp('/symbols/7');

    await userEvent.click(await screen.findByRole('button', { name: tr.symbol.analyzeImpact }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/impact'));
    expect(router.state.location.search).toBe('?symbol=7');
  });

  it('an unknown symbol shows the backend message', async () => {
    signedIn();
    server.use(
      http.get(apiUrl('/api/v1/symbols/404'), () =>
        HttpResponse.json({ status: 404, detail: 'No symbol with id 404' }, { status: 404 })),
      http.get(apiUrl('/api/v1/symbols/404/usages/summary'), () => HttpResponse.json({ status: 404 }, { status: 404 })),
      http.get(apiUrl('/api/v1/symbols/404/usages'), () => HttpResponse.json({ status: 404 }, { status: 404 })),
    );
    renderApp('/symbols/404');

    expect(await screen.findByText('No symbol with id 404')).toBeInTheDocument();
  });

  it('a non-numeric id is not found', async () => {
    signedIn();
    renderApp('/symbols/abc');

    expect(await screen.findByText(tr.errors.notFound)).toBeInTheDocument();
  });

  it('the summary lists classes per module', async () => {
    symbolBackend();
    renderApp('/symbols/7');

    const summary = await screen.findByRole('region', { name: tr.symbol.summary });
    await userEvent.click(within(summary).getByText('SHOP/api'));
    expect(await within(summary).findByText('com.shop.api.CheckoutService')).toBeInTheDocument();
  });
});
```

Adapt the `region` query to how you label the summary section: a `section` with `aria-label={tr.symbol.summary}`, or Mantine `Card` plus `aria-labelledby`. Keep the assertion that classes appear under the repository.

In `frontend/src/auth/auth.test.tsx`, replace every `tr.home.title` (or the Task 1 interim `tr.nav.search`) with `tr.search.title`. The home page is now the search page, and its title is a heading. Every test in that file uses `signedIn`-like handlers of its own. Add a handler for `GET /api/v1/repositories` returning an empty page wherever the home page renders, because `RepositorySelect` loads it.

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test`
Expected: failures; the screens do not exist yet.

- [ ] **Step 3: Implement**

Add to `tr.ts`:

```ts
  search: {
    title: 'Sembol arama',
    query: 'Sınıf, metot veya alan',
    hint: 'Örnekler: PriceFormatter, PriceFormatter#format, com.shop.lib.PriceFormatter#format',
    kind: 'Tür',
    repository: 'Repo',
    anyKind: 'Tüm türler',
    anyRepository: 'Tüm repolar',
    columns: { symbol: 'Sembol', kind: 'Tür', origin: 'Kaynak', usages: 'Kullanım', repositories: 'Repo sayısı' },
    nameOnly: 'Bağlamsız eşleşme',
  },
  symbol: {
    analyzeImpact: 'Etki analizi',
    declarations: 'Tanımlandığı yerler',
    hierarchy: 'Hiyerarşi',
    parent: 'Üst sınıf',
    supertypes: 'Üst tipler',
    subtypes: 'Alt tipler',
    overrides: 'Override ettiği',
    overriddenBy: 'Override eden',
    members: 'Üyeler',
    summary: 'Kullanım özeti',
    summaryTotals: (usages: string, repositories: string) => `${usages} kullanım, ${repositories} repo`,
    usages: 'Kullanımlar',
    filters: { confidence: 'Güven', kind: 'Kullanım türü', repository: 'Repo' },
    columns: { from: 'Kullanan', kind: 'Tür', confidence: 'Güven', location: 'Konum', snippet: 'Kod' },
  },
```

A function value such as `summaryTotals` keeps word order inside `tr`; the `as const` object allows it.

`frontend/src/api/symbols.ts`:

```ts
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, call } from './client';
import type { components } from './schema';

export type SymbolHit = components['schemas']['SymbolHit'];
export type SymbolKind = NonNullable<SymbolHit['kind']>;
export type UsageView = components['schemas']['UsageView'];
export type Confidence = NonNullable<UsageView['confidence']>;
export type UsageKind = NonNullable<UsageView['kind']>;

export function useSymbolSearch(q: string, kind: SymbolKind | undefined, repo: number | undefined, page: number) {
  const text = q.trim();
  return useQuery({
    queryKey: ['symbol-search', text, kind, repo, page],
    enabled: text !== '',
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/symbols/search', { params: { query: { q: text, kind, repo, page } } })),
  });
}

export function useSymbol(id: number) {
  return useQuery({
    queryKey: ['symbol', id],
    queryFn: () => call(api.GET('/api/v1/symbols/{id}', { params: { path: { id } } })),
  });
}

export function useUsageSummary(id: number) {
  return useQuery({
    queryKey: ['usage-summary', id],
    queryFn: () => call(api.GET('/api/v1/symbols/{id}/usages/summary', { params: { path: { id } } })),
  });
}

export interface UsageFilters {
  confidence: Confidence[];
  kind: UsageKind[];
  repo?: number;
}

export function useUsages(id: number, filters: UsageFilters, page: number) {
  return useQuery({
    queryKey: ['usages', id, filters, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/symbols/{id}/usages', {
      params: {
        path: { id },
        query: {
          confidence: filters.confidence.length ? filters.confidence : undefined,
          kind: filters.kind.length ? filters.kind : undefined,
          repo: filters.repo,
          page,
        },
      },
    })),
  });
}
```

`frontend/src/api/repositories.ts`:

```ts
import { useQuery } from '@tanstack/react-query';
import { useUiConfig } from '../config/UiConfigContext';
import { api, call } from './client';

/** Repositories for a filter list: the first page at the largest page size the backend allows. */
export function useRepositoryOptions() {
  const { pageMaxSize } = useUiConfig();
  return useQuery({
    queryKey: ['repository-options', pageMaxSize],
    queryFn: () => call(api.GET('/api/v1/repositories', { params: { query: { size: pageMaxSize } } })),
  });
}
```

`frontend/src/features/search/RepositorySelect.tsx`:

```tsx
import { Select } from '@mantine/core';
import { useRepositoryOptions } from '../../api/repositories';

/** A searchable repository picker; value and onChange carry the repository id. */
export function RepositorySelect({ label, placeholder, value, onChange }: {
  label: string;
  placeholder: string;
  value: number | undefined;
  onChange: (id: number | undefined) => void;
}) {
  const options = useRepositoryOptions();
  const data = (options.data?.items ?? []).map((repo) => ({
    value: String(repo.id),
    label: `${repo.projectKey}/${repo.slug}`,
  }));
  return (
    <Select
      label={label}
      placeholder={placeholder}
      data={data}
      searchable
      clearable
      value={value == null ? null : String(value)}
      onChange={(next) => onChange(next ? Number(next) : undefined)}
    />
  );
}
```

`frontend/src/features/search/SearchPage.tsx`:

```tsx
import { Alert, Badge, Group, Select, Stack, Table, Text, TextInput, Title, Button } from '@mantine/core';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { errorMessage } from '../../api/errors';
import { useSymbolSearch, type SymbolKind } from '../../api/symbols';
import { OriginBadge, SymbolKindBadge } from '../../components/Badges';
import { Loading } from '../../components/Loading';
import { Pager } from '../../components/Pager';
import { useUrlState } from '../../hooks/useUrlState';
import { formatNumber } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { RepositorySelect } from './RepositorySelect';

const KINDS = Object.keys(tr.enums.symbolKind) as SymbolKind[];

/** The home page: find a class, method or field; each overload is its own row (web UI spec §4.1 row 3). */
export function SearchPage() {
  const { params, page, update } = useUrlState();
  const q = params.get('q') ?? '';
  const kind = (params.get('kind') ?? undefined) as SymbolKind | undefined;
  const repoParam = Number(params.get('repo'));
  const repo = Number.isInteger(repoParam) && repoParam > 0 ? repoParam : undefined;
  const results = useSymbolSearch(q, kind, repo, page);
  const navigate = useNavigate();

  return (
    <Stack>
      <Title order={2}>{tr.search.title}</Title>
      <SearchForm key={q} initial={q} onSearch={(text) => update({ q: text })} />
      <Group grow>
        <Select
          label={tr.search.kind}
          placeholder={tr.search.anyKind}
          data={KINDS.map((value) => ({ value, label: tr.enums.symbolKind[value] }))}
          clearable
          value={kind ?? null}
          onChange={(value) => update({ kind: value })}
        />
        <RepositorySelect
          label={tr.search.repository}
          placeholder={tr.search.anyRepository}
          value={repo}
          onChange={(id) => update({ repo: id })}
        />
      </Group>
      {q.trim() === '' ? (
        <Text c="dimmed">{tr.search.hint}</Text>
      ) : results.isPending ? (
        <Loading />
      ) : results.isError ? (
        <Alert color="red">{errorMessage(results.error)}</Alert>
      ) : results.data.items?.length ? (
        <>
          <Table highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.search.columns.symbol}</Table.Th>
                <Table.Th>{tr.search.columns.kind}</Table.Th>
                <Table.Th>{tr.search.columns.origin}</Table.Th>
                <Table.Th>{tr.search.columns.usages}</Table.Th>
                <Table.Th>{tr.search.columns.repositories}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {results.data.items.map((hit) => (
                <Table.Tr key={hit.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/symbols/${hit.id}`)}>
                  <Table.Td>
                    <Text fw={500}>{hit.display ?? hit.key}</Text>
                    <Text size="xs" c="dimmed">{hit.key}</Text>
                    {hit.nameOnly && <Badge size="xs" color="orange">{tr.search.nameOnly}</Badge>}
                  </Table.Td>
                  <Table.Td><SymbolKindBadge value={hit.kind} /></Table.Td>
                  <Table.Td><OriginBadge value={hit.origin} /></Table.Td>
                  <Table.Td>{formatNumber(hit.usageCount)}</Table.Td>
                  <Table.Td>{formatNumber(hit.repositoryCount)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Pager page={page} size={results.data.size} total={results.data.total} onChange={(next) => update({ page: next })} />
        </>
      ) : (
        <Text>{tr.common.empty}</Text>
      )}
    </Stack>
  );
}

/** The query box; keyed by the URL's query so a back/forward navigation resets it. */
function SearchForm({ initial, onSearch }: { initial: string; onSearch: (text: string) => void }) {
  const [text, setText] = useState(initial);
  return (
    <form onSubmit={(event) => { event.preventDefault(); onSearch(text.trim()); }}>
      <Group align="end">
        <TextInput
          style={{ flex: 1 }}
          label={tr.search.query}
          value={text}
          onChange={(event) => setText(event.currentTarget.value)}
        />
        <Button type="submit">{tr.common.search}</Button>
      </Group>
    </form>
  );
}
```

`frontend/src/features/symbol/SymbolPage.tsx`, built from these sections:
- **Header:**
  - `Title order={2}` with `detail.symbol.display`;
  - the key in dimmed text;
  - the kind, origin and "name only" badges;
  - a `Button` labelled `tr.symbol.analyzeImpact` that runs `navigate('/impact?symbol=' + id)`.
- **Declarations:** a table of repository `projectKey/slug`, module, and `filePath:line`. Render the location exactly as `` `${filePath}:${line}` ``.
- **Hierarchy:** a `SymbolLink` list for each non-empty group (parent, supertypes, subtypes, overrides, overriddenBy, members), each with its `tr.symbol` label.
- **`<UsageSummaryView id={id} />`.**
- **`<UsagesTable id={id} />`.**

Parse the route param with `const id = Number(useParams().id)`. If `!Number.isInteger(id) || id <= 0`, return `<NotFoundPage />`. If `useSymbol` fails, render `<ErrorView error={…} onRetry />`; this shows the backend's detail for a 404.

`frontend/src/features/symbol/UsageSummaryView.tsx`:
- a `section` with `aria-label={tr.symbol.summary}`;
- a `Title order={3}`;
- the totals line `tr.symbol.summaryTotals(formatNumber(usages), formatNumber(repositories))`;
- a Mantine `Accordion` with one item per `byRepository` entry: the control shows `projectKey/slug` plus a usage count, and the panel holds a table of modules, each listing its classes with counts;
- a loading or error state of its own (`Loading` / `ErrorView`).

`frontend/src/features/symbol/UsagesTable.tsx`:
- **Filters**, read with `useUrlState()`:
  - `confidence` from `params.getAll('confidence')`;
  - `kind` from `params.getAll('usageKind')`;
  - `repo` from `params.get('repo')`.
- **Controls:**
  - two `MultiSelect`s over `tr.enums.confidence` and `tr.enums.usageKind`;
  - a `Select` over the repositories in `useUsageSummary(id).data.byRepository`.

  Each `onChange` calls `update({ confidence: values })`, `update({ usageKind: values })` or `update({ repo })`.
- **Rows:**
  - `SymbolLink` for `from`;
  - `UsageKindBadge`, `ConfidenceBadge`;
  - the repository and module as `projectKey/slug · modulePath`;
  - `filePath:line`;
  - the snippet in a Mantine `Code`.
- **Paging:** `<Pager page size total onChange={(p) => update({ page: p })} />`.
- **Load failure:** shows `ErrorView`.

`frontend/src/routes.tsx`:
- change the index route to `<SearchPage />`;
- add `{ path: 'symbols/:id', element: <SymbolPage /> }`;
- delete `pages/HomePage.tsx`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git rm frontend/src/pages/HomePage.tsx
git commit -m "feat(frontend): symbol search and symbol detail with usage summary and usages" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 3: Impact analysis

**Files:**
- Create: `frontend/src/api/impact.ts`, `frontend/src/features/impact/impactParams.ts`, `impactElements.ts`, `ImpactPage.tsx`, `ImpactForm.tsx`, `ImpactResultView.tsx`, `ImpactGraphView.tsx`
- Modify: `frontend/package.json`, `frontend/package-lock.json` (add `cytoscape`), `frontend/src/routes.tsx`, `frontend/src/i18n/tr.ts`
- Test: `frontend/src/features/impact/impactParams.test.ts`, `impactElements.test.ts`, `impact.test.tsx`

**Interfaces:**
- **Consumes:**
  - `POST /api/v1/impact` (`ImpactRequest` → `ImpactResult`) and `POST /api/v1/impact/export?format=csv` (same body → CSV file);
  - `useSymbol` (seed names), `useUiConfig`, `useUrlState`, `saveBlob`, `filenameOf`, `notifyError`, and the badges.
- **Produces:**
  - Route `/impact`. Its URL keys:
    - `symbol` (repeated ids);
    - `changeType` (`SIGNATURE` | `BEHAVIOR`; default `BEHAVIOR`, the backend's default);
    - `depth` (default `impactDefaultDepth`, clamped to 1..`impactMaxDepth`);
    - `confidence` (repeated; none means all);
    - `dispatch` (`false` turns it off; default on).
  - `impactRequestFrom(params, uiConfig)` returns the `ImpactRequest`, or `null` when there is no valid symbol. `impactParamsFrom(request)` turns it back into URL changes.
  - `impactElements(result)` builds the Cytoscape elements: nodes keyed by symbol id, edges from → to deduplicated per pair.
  - `ImpactGraphView` renders when `nodes.length <= graphMaxNodes`, otherwise a notice.
  - Each level's usage locations (the edges at that level) are paged client-side by `pageDefaultSize`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/impact/impactParams.test.ts`:

```ts
import { expect, it } from 'vitest';
import { testUiConfig } from '../../test/backend';
import { impactParamsFrom, impactRequestFrom } from './impactParams';

it('builds the request from the URL with UI-config defaults and limits', () => {
  expect(impactRequestFrom(new URLSearchParams('symbol=7&symbol=9'), testUiConfig)).toEqual({
    symbolIds: [7, 9], changeType: 'BEHAVIOR', depth: testUiConfig.impactDefaultDepth, confidences: undefined,
    includeDispatch: true,
  });
  expect(impactRequestFrom(new URLSearchParams('symbol=7&depth=99&changeType=SIGNATURE&confidence=EXACT&dispatch=false'),
    testUiConfig)).toEqual({
    symbolIds: [7], changeType: 'SIGNATURE', depth: testUiConfig.impactMaxDepth, confidences: ['EXACT'],
    includeDispatch: false,
  });
  expect(impactRequestFrom(new URLSearchParams('symbol=abc'), testUiConfig)).toBeNull();
  expect(impactRequestFrom(new URLSearchParams(''), testUiConfig)).toBeNull();
});

it('writes a request back to URL changes', () => {
  expect(impactParamsFrom({ symbolIds: [7], changeType: 'SIGNATURE', depth: 2, confidences: ['EXACT'], includeDispatch: false }))
    .toEqual({ symbol: ['7'], changeType: 'SIGNATURE', depth: 2, confidence: ['EXACT'], dispatch: 'false' });
});
```

`frontend/src/features/impact/impactElements.test.ts`:

```ts
import { expect, it } from 'vitest';
import { impactElements } from './impactElements';

it('turns a result into graph nodes and de-duplicated edges', () => {
  const elements = impactElements({
    nodes: [
      { symbolId: 1, display: 'format(int)', level: 0, role: 'SEED' },
      { symbolId: 2, display: 'total()', level: 1, role: 'AFFECTED' },
    ],
    edges: [
      { fromSymbolId: 2, toSymbolId: 1, kind: 'CALL', level: 1 },
      { fromSymbolId: 2, toSymbolId: 1, kind: 'CALL', level: 1 },
    ],
  });

  expect(elements.filter((e) => e.group === 'nodes').map((e) => e.data.id)).toEqual(['1', '2']);
  expect(elements.filter((e) => e.group === 'edges')).toHaveLength(1);
  expect(elements.find((e) => e.data.id === '1')?.data.role).toBe('SEED');
});
```

`frontend/src/features/impact/impact.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn, testUiConfig } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

vi.mock('cytoscape', () => ({ default: vi.fn(() => ({ on: vi.fn(), destroy: vi.fn() })) }));

const repo = { id: 3, projectKey: 'SHOP', slug: 'api' };

function result(nodeCount = 2, edgeCount = 1) {
  const nodes = [{ symbolId: 7, key: 'k#format(int)', kind: 'METHOD', display: 'format(int)', level: 0, role: 'SEED', confidence: 'EXACT' }];
  for (let i = 1; i < nodeCount; i++) {
    nodes.push({ symbolId: 100 + i, key: `k#caller${i}()`, kind: 'METHOD', display: `caller${i}()`, level: 1, role: 'AFFECTED', confidence: 'EXACT' });
  }
  const edges = Array.from({ length: edgeCount }, (_, i) => ({
    fromSymbolId: 101, toSymbolId: 7, kind: 'CALL', confidence: 'EXACT', level: 1, viaDispatch: false, repository: repo,
    modulePath: 'shop-api', filePath: 'CheckoutService.java', line: 10 + i, column: 1, snippet: `call${i}()`,
  }));
  return {
    summary: { repositories: 1, modules: 1, classes: 1, methods: nodeCount - 1, usages: edgeCount },
    nodes, edges,
    entryPoints: [{ symbolId: 101, key: 'k#checkout()', display: 'checkout()', repository: repo, modulePath: 'shop-api',
      label: 'HTTP', annotation: 'org.springframework.web.bind.annotation.PostMapping', http: true, httpMethod: 'POST',
      httpPath: '/orders/checkout' }],
    staleness: [], versionWarnings: [{ repository: repo, modulePath: 'shop-api', dependency: 'com.shop:shop-lib',
      usedVersion: '1.0.0', declaredVersion: '1.2.0' }],
    truncated: false,
  };
}

function impactBackend(options: { body?: unknown; uiConfig?: Partial<typeof testUiConfig>; requests?: unknown[]; exports?: unknown[] } = {}) {
  signedIn({ uiConfig: options.uiConfig });
  server.use(
    http.get(apiUrl('/api/v1/symbols/7'), () => HttpResponse.json({
      symbol: { id: 7, key: 'k#format(int)', kind: 'METHOD', display: 'format(int)' },
    })),
    http.post(apiUrl('/api/v1/impact'), async ({ request }) => {
      options.requests?.push(await request.json());
      return HttpResponse.json(options.body ?? result());
    }),
    http.post(apiUrl('/api/v1/impact/export'), async ({ request }) => {
      options.exports?.push({ format: new URL(request.url).searchParams.get('format'), body: await request.json() });
      return new HttpResponse('level,symbol\n1,checkout()\n', {
        headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="impact.csv"' },
      });
    }),
  );
}

afterEach(() => vi.restoreAllMocks());

describe('impact analysis', () => {
  it('runs the analysis from the link and exports the same request', async () => {
    const requests: unknown[] = [];
    const exports: unknown[] = [];
    impactBackend({ requests, exports });
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    renderApp('/impact?symbol=7&depth=2&confidence=EXACT');

    expect(await screen.findByText('/orders/checkout')).toBeInTheDocument();
    const expected = { symbolIds: [7], changeType: 'BEHAVIOR', depth: 2, confidences: ['EXACT'], includeDispatch: true };
    expect(requests).toEqual([expected]);

    await userEvent.click(screen.getByRole('button', { name: tr.impact.exportCsv }));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(exports).toEqual([{ format: 'csv', body: expected }]);
  });

  it('uses the configured default depth and writes the form to the URL', async () => {
    const requests: unknown[] = [];
    impactBackend({ requests });
    const router = renderApp('/impact?symbol=7');

    await screen.findByText('/orders/checkout');
    expect(requests).toEqual([expect.objectContaining({ depth: testUiConfig.impactDefaultDepth })]);

    await userEvent.click(screen.getByLabelText(tr.enums.changeType.SIGNATURE));
    await userEvent.click(screen.getByRole('button', { name: tr.impact.run }));

    await waitFor(() => expect(router.state.location.search).toContain('changeType=SIGNATURE'));
    await waitFor(() => expect(requests).toHaveLength(2));
  });

  it('shows warnings and the seed by name', async () => {
    impactBackend();
    renderApp('/impact?symbol=7');

    expect(await screen.findByText('com.shop:shop-lib')).toBeInTheDocument();
    expect(screen.getAllByText('format(int)').length).toBeGreaterThan(0);
  });

  it('a result over the node limit shows no graph and pages its locations', async () => {
    impactBackend({ body: { ...result(5, 3), truncated: true }, uiConfig: { graphMaxNodes: 4, pageDefaultSize: 2 } });
    renderApp('/impact?symbol=7');

    expect(await screen.findByText(tr.impact.truncated)).toBeInTheDocument();
    expect(screen.getByText(tr.impact.graphTooLarge(5, 4))).toBeInTheDocument();
    expect(screen.queryByTestId('impact-graph')).not.toBeInTheDocument();
    expect(screen.getByText('call0()')).toBeInTheDocument();
    expect(screen.queryByText('call2()')).not.toBeInTheDocument();
  });

  it('shows the backend message for a rejected request', async () => {
    signedIn();
    server.use(
      http.get(apiUrl('/api/v1/symbols/7'), () => HttpResponse.json({ symbol: { id: 7, display: 'format(int)' } })),
      http.post(apiUrl('/api/v1/impact'), () =>
        HttpResponse.json({ status: 400, detail: 'depth must be between 1 and 10' }, { status: 400 })),
    );
    renderApp('/impact?symbol=7');

    expect(await screen.findByText('depth must be between 1 and 10')).toBeInTheDocument();
  });

  it('asks for a symbol when none is given', async () => {
    signedIn();
    renderApp('/impact');

    expect(await screen.findByText(tr.impact.noSymbol)).toBeInTheDocument();
  });
});
```

How the location paging assertion works: with `pageDefaultSize: 2`, the level's three locations are split over two pages. `call0()` and `call1()` show first, and `call2()` is on page 2. If the level sections are collapsed by default, expand level 1 in the test before asserting. The meaning stays the same.

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test`
Expected: failures; the modules do not exist yet.

- [ ] **Step 3: Implement**

Run: `npm install cytoscape` (in `frontend/`). Commit the lockfile.

Add to `tr.ts`:

```ts
  impact: {
    title: 'Etki analizi',
    noSymbol: 'Analiz için önce bir sembol seçin (sembol arama → sembol → Etki analizi).',
    seeds: 'Değişecek semboller',
    removeSeed: 'Çıkar',
    changeType: 'Değişiklik tipi',
    depth: 'Derinlik',
    confidences: 'Güven seviyeleri',
    dispatch: 'Dinamik bağlanmayı (override/arayüz) dahil et',
    run: 'Analiz et',
    exportCsv: 'CSV indir',
    csvFileName: 'etki-analizi.csv',
    truncated: 'Sonuç sınırı aşıldı; liste eksik olabilir. Derinliği veya güven seviyelerini daraltın.',
    summary: { repositories: 'Repo', modules: 'Modül', classes: 'Sınıf', methods: 'Metot', usages: 'Kullanım' },
    partialClasspath: 'Classpath\'i eksik çözülmüş repolar (sonuçlar eksik olabilir)',
    staleness: 'İndeks durumu',
    versionWarnings: 'Sürüm uyarıları',
    versionColumns: { repository: 'Repo / modül', dependency: 'Bağımlılık', used: 'Kullanılan', declared: 'Tanımlı' },
    entryPoints: 'Giriş noktaları',
    entryColumns: { entry: 'Giriş', symbol: 'Metot', repository: 'Repo / modül' },
    levels: 'Seviyeler',
    level: (level: number) => (level === 0 ? 'Değişen semboller' : `Seviye ${level}`),
    locations: 'Kullanım yerleri',
    graph: 'Etki grafı',
    graphTooLarge: (nodes: number, max: number) => `Graf çizilmedi: ${nodes} düğüm, sınır ${max}.`,
  },
```

`frontend/src/features/impact/impactParams.ts`:

```ts
import type { components } from '../../api/schema';
import type { UiConfig } from '../../config/UiConfigContext';
import type { UrlValue } from '../../hooks/useUrlState';

export type ImpactRequest = components['schemas']['ImpactRequest'];
type ChangeType = NonNullable<ImpactRequest['changeType']>;
type Confidence = NonNullable<ImpactRequest['confidences']>[number];

const CHANGE_TYPES: readonly ChangeType[] = ['SIGNATURE', 'BEHAVIOR'];
const CONFIDENCES: readonly Confidence[] = ['EXACT', 'RECOVERED', 'NAME_ONLY'];
/** The backend's own default when no change type is sent (impact spec §5.3). */
const DEFAULT_CHANGE_TYPE: ChangeType = 'BEHAVIOR';

/** The analysis a URL describes, or null when it names no valid symbol. Limits come from /ui-config. */
export function impactRequestFrom(params: URLSearchParams, config: Pick<UiConfig, 'impactDefaultDepth' | 'impactMaxDepth'>): ImpactRequest | null {
  const symbolIds = params.getAll('symbol').map(Number).filter((id) => Number.isInteger(id) && id > 0);
  if (symbolIds.length === 0) {
    return null;
  }
  const changeType = CHANGE_TYPES.find((value) => value === params.get('changeType')) ?? DEFAULT_CHANGE_TYPE;
  const rawDepth = Number(params.get('depth'));
  const depth = Number.isInteger(rawDepth) && rawDepth > 0
    ? Math.min(rawDepth, config.impactMaxDepth)
    : config.impactDefaultDepth;
  const confidences = params.getAll('confidence').filter((value): value is Confidence =>
    (CONFIDENCES as readonly string[]).includes(value));
  return {
    symbolIds,
    changeType,
    depth,
    confidences: confidences.length ? confidences : undefined,
    includeDispatch: params.get('dispatch') !== 'false',
  };
}

/** URL changes that describe a request (the inverse of impactRequestFrom). */
export function impactParamsFrom(request: ImpactRequest): Record<string, UrlValue> {
  return {
    symbol: (request.symbolIds ?? []).map(String),
    changeType: request.changeType,
    depth: request.depth,
    confidence: request.confidences ?? null,
    dispatch: request.includeDispatch === false ? 'false' : null,
  };
}
```

If `UiConfig` fields are optional in the generated type, `Pick` still works. The provider guarantees values, and Plan 9 marked them required.

`frontend/src/api/impact.ts`:

```ts
import { useQuery } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { ApiError, api, call, type Problem } from './client';
import { filenameOf, saveBlob } from './download';
import type { ImpactRequest } from '../features/impact/impactParams';

/** POST /impact as a query: the analysis is idempotent, so a shared link re-runs it (web UI spec §4.1 row 5). */
export function useImpact(request: ImpactRequest | null) {
  return useQuery({
    queryKey: ['impact', request],
    enabled: request !== null,
    queryFn: () => call(api.POST('/api/v1/impact', { body: request! })),
  });
}

/** CSV of the same analysis, saved under the name the backend suggests. */
export async function exportImpactCsv(request: ImpactRequest): Promise<void> {
  const { data, error, response } = await api.POST('/api/v1/impact/export', {
    params: { query: { format: 'csv' } },
    body: request,
    parseAs: 'blob',
  });
  if (!response.ok || !(data instanceof Blob)) {
    throw new ApiError(response.status, (typeof error === 'object' && error !== null ? error : {}) as Problem);
  }
  saveBlob(data, filenameOf(response.headers.get('Content-Disposition')) ?? tr.impact.csvFileName);
}
```

If `openapi-fetch` types `data` for `parseAs: 'blob'` differently, keep the runtime `instanceof Blob` check and cast as needed.

`frontend/src/features/impact/impactElements.ts`:

```ts
import type { ElementDefinition } from 'cytoscape';
import type { components } from '../../api/schema';

type ImpactResult = Pick<components['schemas']['ImpactResult'], 'nodes' | 'edges'>;

/** Graph elements for an impact result: one node per symbol, one edge per (from, to) pair. */
export function impactElements(result: ImpactResult): ElementDefinition[] {
  const nodes: ElementDefinition[] = (result.nodes ?? [])
    .filter((node) => node.symbolId != null)
    .map((node) => ({
      group: 'nodes',
      data: { id: String(node.symbolId), label: node.display ?? node.key ?? '', level: node.level ?? 0, role: node.role ?? '' },
    }));
  const ids = new Set(nodes.map((node) => node.data.id));
  const seen = new Set<string>();
  const edges: ElementDefinition[] = [];
  for (const edge of result.edges ?? []) {
    const from = String(edge.fromSymbolId);
    const to = String(edge.toSymbolId);
    const id = `${from}->${to}`;
    if (!ids.has(from) || !ids.has(to) || seen.has(id)) {
      continue;
    }
    seen.add(id);
    edges.push({ group: 'edges', data: { id, source: from, target: to } });
  }
  return [...nodes, ...edges];
}
```

`frontend/src/features/impact/ImpactGraphView.tsx`:

```tsx
import cytoscape from 'cytoscape';
import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router';
import type { components } from '../../api/schema';
import { impactElements } from './impactElements';

type ImpactResult = components['schemas']['ImpactResult'];

/** Presentational graph styling: seeds stand out, callers are drawn level by level from the seeds. */
const GRAPH_STYLE: cytoscape.StylesheetJson = [
  { selector: 'node', style: { label: 'data(label)', 'font-size': 10, 'background-color': '#4c6ef5', width: 18, height: 18 } },
  { selector: 'node[role = "SEED"]', style: { 'background-color': '#e03131', width: 26, height: 26 } },
  { selector: 'node[role = "DISPATCH"]', style: { 'background-color': '#f08c00' } },
  { selector: 'edge', style: { width: 1, 'line-color': '#adb5bd', 'target-arrow-shape': 'triangle', 'target-arrow-color': '#adb5bd', 'curve-style': 'bezier' } },
];

export function ImpactGraphView({ result }: { result: ImpactResult }) {
  const container = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!container.current) {
      return;
    }
    const seeds = (result.nodes ?? []).filter((node) => node.role === 'SEED').map((node) => `#${node.symbolId}`);
    const graph = cytoscape({
      container: container.current,
      elements: impactElements(result),
      style: GRAPH_STYLE,
      layout: { name: 'breadthfirst', directed: false, roots: seeds.join(', '), spacingFactor: 1.2 },
    });
    graph.on('tap', 'node', (event) => navigate(`/symbols/${event.target.id()}`));
    return () => graph.destroy();
  }, [result, navigate]);

  return <div ref={container} data-testid="impact-graph" style={{ height: 420, width: '100%' }} />;
}
```

If the installed Cytoscape types name the stylesheet type differently (`Stylesheet[]`, `StylesheetJson`), use the exported one. If a Cytoscape id selector with a numeric id needs escaping, prefix node ids (e.g. `s7`) in `impactElements` and strip the prefix on tap. Update the unit test's expected ids accordingly.

`frontend/src/features/impact/ImpactForm.tsx`: a form over a draft `ImpactRequest`, initialised from `impactRequestFrom` and keyed on the URL search string so navigation resets it. It contains:
- **Seeds:** a `SymbolLink`/display for each id, using `useSymbol(id)` for the name, each with a `tr.impact.removeSeed` button.
- **Change type:** a `Radio.Group` with one `Radio` per change type; each radio's label is `tr.enums.changeType[...]`, so `getByLabelText` works.
- **Depth:** a `NumberInput` with `min={1}` and `max={impactMaxDepth}`.
- **Confidences:** a `Checkbox.Group` over `tr.enums.confidence`.
- **Dispatch:** a `Switch` labelled `tr.impact.dispatch`.
- **Submit:** a `Button type="submit"` labelled `tr.impact.run`; it calls `update(impactParamsFrom(draft))`.

`frontend/src/features/impact/ImpactPage.tsx`:
- reads `useUrlState()` and `useUiConfig()`;
- `request = impactRequestFrom(params, config)`;
- with no request, shows `tr.impact.noSymbol`;
- otherwise renders `ImpactForm` and `useImpact(request)`, then `Loading`, `ErrorView` or `<ImpactResultView result request />`.

`frontend/src/features/impact/ImpactResultView.tsx`, in this order:
1. **Actions:** a `Button` labelled `tr.impact.exportCsv`. On click it calls `exportImpactCsv(request).catch(notifyError)` and shows a loading state while the export runs.
2. **Truncated notice:** when `result.truncated`, an `Alert` with `tr.impact.truncated`.
3. **Summary:** a `SimpleGrid` of the five summary numbers with `tr.impact.summary` labels, formatted with `formatNumber`.
4. **Partial classpaths:** when `summary.partialClasspathRepositories` is non-empty, an `Alert` listing them.
5. **Version warnings:** a table with `tr.impact.versionColumns`.
6. **Index state:** the staleness table (`repository`/`modulePath`/`classpathMode` via `tr.enums.classpathMode`/`lastIndexedCommit`/`formatDateTime(lastIndexedAt)`), shown when non-empty.
7. **Entry points:** a table whose entry column shows, for HTTP ones, the `httpMethod` as a `Badge` and the `httpPath` as its own `Text`, so the path is a separate text node the test finds exactly; otherwise it shows `label` (annotation). Each row also has a `SymbolLink` and the repository/module.
8. **Graph:**
   - if `nodes.length <= graphMaxNodes`, `<ImpactGraphView result />`;
   - otherwise `<Text>{tr.impact.graphTooLarge(nodes.length, graphMaxNodes)}</Text>`.
9. **Levels:** an `Accordion` with one item per distinct node level, sorted ascending, and every item open by default. Each item holds:
   - the level's nodes: `SymbolLink`, role badge (`tr.enums.nodeRole`) and `ConfidenceBadge`;
   - for levels ≥ 1, the level's edges as usage locations: `SymbolLink` to the from-symbol, `UsageKindBadge`, `ConfidenceBadge`, `repository/module`, `filePath:line` and `Code` snippet. Paginate them client-side with `Pager` using `size = pageDefaultSize` and local page state.

In `frontend/src/routes.tsx`, add `{ path: 'impact', element: <ImpactPage /> }`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src
git commit -m "feat(frontend): impact analysis with entry points, warnings, graph and CSV export" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 4: Repositories and index runs

**Files:**
- Create: `frontend/src/api/runs.ts`, `frontend/src/features/repositories/RepositoriesPage.tsx`, `RepositoryPage.tsx`, `frontend/src/features/runs/RunsPage.tsx`, `RunPage.tsx`, `RunRepositoriesTable.tsx`
- Modify: `frontend/src/api/repositories.ts`, `frontend/src/routes.tsx`, `frontend/src/i18n/tr.ts`, `README.md`
- Test: `frontend/src/features/repositories/repositories.test.tsx`, `frontend/src/features/runs/runs.test.tsx`

**Interfaces:**
- **Consumes:**
  - `GET /api/v1/repositories?q&status&page` → `PageRepositorySummary`
  - `GET /api/v1/repositories/{id}` → `RepositoryDetail`
  - `GET /api/v1/repositories/{id}/runs?page` → `PageIndexRunRepoView`
  - `GET /api/v1/index/runs?page` → `PageIndexRunSummary`
  - `GET /api/v1/index/runs/{runId}` → `IndexRunView`
- **Produces:**
  - Routes: `/repositories` (`?q&status&page`), `/repositories/:id` (`?page` for the run history), `/runs` (`?page`) and `/runs/:id`.
  - Hooks: `useRepositories`, `useRepository`, `useRepositoryRuns` (`api/repositories.ts`); `useRuns`, `useRun` (`api/runs.ts`). `useRun` refetches every `pollIntervalMillis` while `run.status === 'RUNNING'`, and stops otherwise.
  - `README.md`: a short "Web UI screens" list under `## Web UI`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/repositories/repositories.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

const summary = { id: 3, projectKey: 'SHOP', slug: 'api', defaultBranch: 'main', lastIndexedCommit: 'abc123def456',
  lastIndexedAt: '2026-10-07T09:00:00Z', lastStatus: 'SUCCESS_PARTIAL', active: true, moduleCount: 2 };

describe('repositories', () => {
  it('lists repositories and filters by status through the URL', async () => {
    const seen: URL[] = [];
    signedIn();
    server.use(http.get(apiUrl('/api/v1/repositories'), ({ request }) => {
      seen.push(new URL(request.url));
      return HttpResponse.json({ items: [summary], page: 0, size: 50, total: 1 });
    }));
    const router = renderApp('/repositories');

    expect(await screen.findByText('SHOP/api')).toBeInTheDocument();
    expect(screen.getByText(tr.enums.repoStatus.SUCCESS_PARTIAL)).toBeInTheDocument();

    await userEvent.click(screen.getByLabelText(tr.repositories.status));
    await userEvent.click(await screen.findByRole('option', { name: tr.enums.repoStatus.FAILED }));

    await waitFor(() => expect(router.state.location.search).toBe('?status=FAILED'));
    await waitFor(() => expect(seen.at(-1)?.searchParams.get('status')).toBe('FAILED'));
  });

  it('shows a repository with its modules and run history', async () => {
    signedIn();
    server.use(
      http.get(apiUrl('/api/v1/repositories/3'), () => HttpResponse.json({ repository: summary, modules: [
        { id: 30, path: 'shop-api', groupId: 'com.shop', artifactId: 'shop-api', version: '1.0.0', classpathMode: 'FULL' },
        { id: 31, path: 'shop-legacy', groupId: 'com.shop', artifactId: 'shop-legacy', version: '1.0.0', classpathMode: 'NONE' },
      ] })),
      http.get(apiUrl('/api/v1/repositories/3/runs'), () => HttpResponse.json({ items: [
        { runId: 41, repository: { id: 3, projectKey: 'SHOP', slug: 'api' }, commit: 'abc123def456', status: 'CLONE_FAILED',
          error: 'clone of https://***@scm/x failed', symbolCount: 0, usageCount: 0, warningCount: 0, durationMs: 1200,
          finishedAt: '2026-10-07T09:00:00Z' },
      ], page: 0, size: 50, total: 1 })),
    );
    renderApp('/repositories/3');

    expect(await screen.findByText('com.shop:shop-legacy:1.0.0')).toBeInTheDocument();
    expect(screen.getByText(tr.enums.classpathMode.NONE)).toBeInTheDocument();
    expect(await screen.findByText('clone of https://***@scm/x failed')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '#41' })).toHaveAttribute('href', '/runs/41');
  });
});
```

`frontend/src/features/runs/runs.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

const repoRef = { id: 3, projectKey: 'SHOP', slug: 'api' };

function run(status: string) {
  return {
    run: { id: 9, trigger: 'MANUAL', scope: 'ALL', status, startedBy: 'ayse', startedAt: '2026-10-07T09:00:00Z',
      finishedAt: status === 'RUNNING' ? undefined : '2026-10-07T09:05:00Z', cancelRequested: false },
    repositoriesByStatus: status === 'RUNNING' ? { SUCCESS: 1 } : { SUCCESS: 2 },
    repositories: [{ runId: 9, repository: repoRef, commit: 'abc', status: 'SUCCESS', symbolCount: 10, usageCount: 20,
      warningCount: 0, durationMs: 1000 }],
    inProgress: status === 'RUNNING' ? [{ id: 4, projectKey: 'SHOP', slug: 'lib' }] : [],
  };
}

describe('index runs', () => {
  it('lists runs with Turkish trigger, scope and status', async () => {
    signedIn();
    server.use(http.get(apiUrl('/api/v1/index/runs'), () => HttpResponse.json({ items: [run('SUCCESS').run],
      page: 0, size: 50, total: 1 })));
    renderApp('/runs');

    expect(await screen.findByRole('link', { name: '#9' })).toHaveAttribute('href', '/runs/9');
    expect(screen.getByText(tr.enums.runTrigger.MANUAL)).toBeInTheDocument();
    expect(screen.getByText(tr.enums.runScope.ALL)).toBeInTheDocument();
    expect(screen.getByText(tr.enums.runStatus.SUCCESS)).toBeInTheDocument();
  });

  it('a running run refreshes until it finishes', async () => {
    let calls = 0;
    signedIn({ uiConfig: { pollIntervalMillis: 20 } });
    server.use(http.get(apiUrl('/api/v1/index/runs/9'), () => {
      calls++;
      return HttpResponse.json(run(calls < 3 ? 'RUNNING' : 'SUCCESS'));
    }));
    renderApp('/runs/9');

    expect(await screen.findByText('SHOP/lib')).toBeInTheDocument();
    expect(screen.getByText(tr.enums.runStatus.RUNNING)).toBeInTheDocument();
    expect(await screen.findByText(tr.enums.runStatus.SUCCESS, {}, { timeout: 2000 })).toBeInTheDocument();
    const settled = calls;
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(calls).toBe(settled);
  });
});
```

`screen.getByText(tr.enums.runStatus.SUCCESS)` may match more than one element, for example the run badge and a per-repository status label. In that case, scope the query to the run header with `within` (give the header an `aria-label` such as `tr.runs.title`). Keep the polling assertions.

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test`
Expected: failures; the screens do not exist yet.

- [ ] **Step 3: Implement**

Add to `tr.ts`:

```ts
  repositories: {
    title: 'Repolar',
    query: 'Proje veya repo adı',
    status: 'Son tarama durumu',
    anyStatus: 'Tüm durumlar',
    columns: { repository: 'Repo', status: 'Durum', indexedAt: 'Son tarama', commit: 'Commit', modules: 'Modül', active: 'Aktif' },
    inactive: 'Pasif',
    modules: 'Modüller',
    moduleColumns: { path: 'Yol', coordinates: 'Maven koordinatı', classpath: 'Classpath' },
    runs: 'Tarama geçmişi',
    runColumns: { run: 'Tarama', status: 'Durum', commit: 'Commit', counts: 'Sembol / kullanım / uyarı', duration: 'Süre', finishedAt: 'Bitiş', error: 'Hata' },
    durationSeconds: (seconds: string) => `${seconds} sn`,
  },
  runs: {
    title: 'Taramalar',
    columns: { run: 'Tarama', trigger: 'Başlatan', scope: 'Kapsam', status: 'Durum', startedBy: 'Kullanıcı', startedAt: 'Başlangıç', finishedAt: 'Bitiş' },
    cancelRequested: 'İptal istendi',
    inProgress: 'Şu an taranan repolar',
    byStatus: 'Repo durumları',
    repositories: 'Repo sonuçları',
    error: 'Hata',
  },
```

`frontend/src/api/repositories.ts`: keep `useRepositoryOptions` and add:

```ts
export function useRepositories(q: string, status: string | undefined, page: number) {
  return useQuery({
    queryKey: ['repositories', q, status, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/repositories', { params: { query: { q: q || undefined, status, page } } })),
  });
}

export function useRepository(id: number) {
  return useQuery({
    queryKey: ['repository', id],
    queryFn: () => call(api.GET('/api/v1/repositories/{id}', { params: { path: { id } } })),
  });
}

export function useRepositoryRuns(id: number, page: number) {
  return useQuery({
    queryKey: ['repository-runs', id, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/repositories/{id}/runs', { params: { path: { id }, query: { page } } })),
  });
}
```

Import `keepPreviousData`.

`frontend/src/api/runs.ts`:

```ts
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useUiConfig } from '../config/UiConfigContext';
import { api, call } from './client';

export function useRuns(page: number) {
  return useQuery({
    queryKey: ['runs', page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/index/runs', { params: { query: { page } } })),
  });
}

/** One run; while it is RUNNING it is refreshed every ui.poll_interval (web UI spec §4.1 row 8). */
export function useRun(id: number) {
  const { pollIntervalMillis } = useUiConfig();
  return useQuery({
    queryKey: ['run', id],
    queryFn: () => call(api.GET('/api/v1/index/runs/{runId}', { params: { path: { runId: id } } })),
    refetchInterval: (query) => (query.state.data?.run?.status === 'RUNNING' ? pollIntervalMillis : false),
  });
}
```

**`RepositoriesPage`:**
- **Filters:** a text query form (submit), keyed by `q` like the search form, and a status `Select` labelled `tr.repositories.status` over `tr.enums.repoStatus`. Both write the URL through `update`.
- **Table:** an `Anchor` to `/repositories/{id}` with text `projectKey/slug`, `RepoStatusBadge`, `formatDateTime(lastIndexedAt)`, the commit in `Code` (full value; truncated visually with CSS `maxWidth` and `title`), `formatNumber(moduleCount)`, and a badge `tr.repositories.inactive` when inactive.
- **Paging:** `Pager`.

**`RepositoryPage`:**
- The id is parsed as in Task 2; an invalid id renders `NotFoundPage`.
- **Header:** `projectKey/slug`, status, branch, commit and last indexed time.
- **Modules table:** coordinates rendered exactly as `` `${groupId}:${artifactId}:${version}` ``, each part replaced by `tr.common.none` when missing; classpath via `tr.enums.classpathMode`.
- **Run history:** `useRepositoryRuns` with `?page`. Each row has an `Anchor` to `/runs/{runId}` with text `` `#${runId}` ``, a `RepoStatusBadge`, the commit, the counts and the duration (`tr.repositories.durationSeconds(formatNumber(durationMs / 1000))`), `formatDateTime(finishedAt)`, and the error in red text.

**`RunsPage`:** a paged table. Each row has an `Anchor` to `/runs/{id}` with text `` `#${id}` ``, the trigger via `tr.enums.runTrigger`, the scope via `tr.enums.runScope` plus `scopeId` when present, a `RunStatusBadge`, `startedBy`, and both times. Add a badge `tr.runs.cancelRequested` when the run has `cancelRequested`.

**`RunPage`:**
- **Header** (`aria-label={tr.runs.title}`): run status, trigger, scope, times and error.
- **Counts:** `repositoriesByStatus` as badges, each labelled `enumLabel(tr.enums.repoStatus, key): count`.
- **In progress:** an `inProgress` list (`projectKey/slug`), shown only when non-empty.
- **Repositories:** `<RunRepositoriesTable rows={view.repositories} />`, with the same columns as the repository run history but showing the repository instead of the run id.

In `frontend/src/routes.tsx`, add:

```tsx
      { path: 'repositories', element: <RepositoriesPage /> },
      { path: 'repositories/:id', element: <RepositoryPage /> },
      { path: 'runs', element: <RunsPage /> },
      { path: 'runs/:id', element: <RunPage /> },
```

`README.md`, under `## Web UI`: add "Screens" with one line each for search, symbol detail, impact (shareable link, CSV), repositories and runs (live refresh every `ui.poll_interval`). Admin screens follow in Plan 12.

- [ ] **Step 4: Run the tests and the build**

Run (in `frontend/`): `npm test && npm run build`
Expected: all pass.

Run from the repository root: `./mvnw -q package -DskipTests -Dfrontend.node.downloadRoot=https://nodejs.org/dist/ -Dfrontend.npm.registry=https://registry.npmjs.org`
Expected: the WAR builds and contains the new UI.

If a browser automation tool is available, check the new screens on the WildFly gate:
- **Setup:** start Oracle on host port 1522 and WildFly from `/tmp/wildfly-gate/wildfly-41.0.0.Final`, with `JAVA_OPTS=-Djboss.socket.binding.port-offset=1000` and `WILDFLY_HTTP_PORT=9080`, as in Plan 9.
- **Never touch:** the user's WildFly on 8080, or the container `fw-batch-oracle`.
- **What to check:** sign in, open every new screen once, and reload one deep link of each kind (`/graphify/symbols/1`, `/graphify/impact?symbol=1`, `/graphify/repositories`, `/graphify/runs`). With an empty index, expect the empty and not-found views. Confirm the console has no CSP violations.
- **Clean up:** remove the Oracle container and stop the gate WildFly.

Report what you checked.

- [ ] **Step 5: Commit**

```bash
git add frontend/src README.md
git commit -m "feat(frontend): repositories with run history and index runs with live progress" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```
