# Plan 12: Admin Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give admins every management screen from web UI spec §4.2, so no database or API access is needed to run the system:
- start and cancel index runs;
- manage SCM connections and Maven repositories, including a connection test;
- manage users (add them from LDAP, change role, activate or deactivate);
- edit the LDAP settings;
- edit application settings;
- edit entry-point annotations and impact rules;
- read the audit log.

Secrets are never shown, filled in or stored in the browser (spec §4.3).

**Architecture:**
- **Routing:** an `admin/*` route subtree behind `<RequireRole role="ADMIN">`. A USER who opens such a URL directly sees a forbidden notice, and no admin request is sent. The menu shows the admin items only to admins, in the existing "Yönetim" group.
- **Data:** one hook file per admin resource in `src/api/`. Reads use `useQuery`. Writes use `useMutation` with `notifyError` on failure; on success they invalidate the affected queries and show a Turkish success notification.
- **Shared parts:**
  - `SecretField` and its pure helpers implement spec §4.3: keep, clear, replace, and re-entry when the target changes.
  - `ConfirmButton` guards destructive actions with a confirmation modal.
  - `SyncStatusBadge` shows connection test and sync results.
- **Index runs:** admins get a start form on the runs page and a scan button on a repository page. A 409 conflict shows the backend's message inline, with a link to the run in progress. Cancelling a run asks for confirmation.

**Tech Stack:** React 19, TypeScript 5.9, Mantine 9 (`TagsInput`, `PasswordInput`, `Modal`, `Switch`, `Select`), React Router 8, TanStack Query 5, openapi-fetch, Vitest + Testing Library + MSW. No new dependency.

**Spec:**
- `docs/superpowers/specs/2026-10-06-web-ui-design.md` §4.2 rows 9–17, §4.3, §6.2, §7 rows 3–4.
- Backend endpoints in `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` §10.5–10.6, as implemented by the controllers under `src/main/java/com/graphify/{indexing,scm,maven,auth,settings,impact,audit}`.

**Backend facts the screens rely on (read from the code):**
- **Secrets in full updates:**
  - A null or absent secret keeps the stored one only while the target is unchanged:
    - SCM: `baseUrl` and `username`;
    - Maven: `url` and `username`;
    - LDAP: `url` and `bindDn`.
  - Otherwise the update gets a 400 "Re-enter the …".
  - `""` clears the secret. Responses carry only `secretSet` / `bindPasswordSet`.
- **Run conflicts:** `POST /index/runs` answers 409 with `runId` as a problem property when another run is in progress (`IndexRunConflictException`).
- **Deletes:**
  - `DELETE` answers 204.
  - Deleting an SCM connection that still has repositories answers 409 ("disable it instead").
  - Deactivating or demoting the last admin answers 409.
- **Connection tests:** `POST …/{id}/test` tests the stored configuration and answers `{ok: true}`, or 502 with a masked message. `POST /admin/ldap/test` tests the posted (unsaved) configuration, merged with the stored bind password under the same re-entry rule.
- **Users:** `POST /admin/users` registers a directory user (`{username, role}`). `GET /admin/ldap/users?q=` returns `DirectoryUserView` (`username`, `displayName`, `email`, `appUserId` when already registered).
- **Settings:** values are strings. `LIST` is comma-separated, `DURATION` is ISO-8601, and `CRON` has 6 fields. A bad value answers 400 with the reason.
- **Audit:** `from` and `to` are ISO-8601 instants.

**Rulings taken while planning:**
- **Form errors.** The backend's 400 text appears in the form, in an `Alert` above the buttons, and as a notification. Spec §6.4 asks for messages under the field; the backend's messages do not name a field reliably, so only the settings rows (one field each) show the message under the field.
- **Connection tests run on saved data.** The SCM and Maven "test" buttons test what is saved, so they appear on the edit page of a saved item only, and are disabled while the form has unsaved changes. The LDAP test sends the form as it is, because the backend supports that.
- **No date-picker dependency.** The audit date filters use native `datetime-local` inputs converted to ISO instants (`@mantine/dates` would be a new dependency).
- **Users are added through the directory.** Admins add users by searching the directory; there is no free-text username field, because the backend only registers directory users.
- **The 409 run-conflict link is inline.** It appears in the start form, not in a notification. Notifications render outside the router, so a link inside one could not navigate.
- **No hand-written API types.** The generated `components['schemas']` types are used everywhere. `Problem` gains an optional `runId`, the documented property of a run conflict.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–8 | Backend | merged |
| 9–11 | Web foundation, user screens, repository graph screen | merged |
| **12** | **Admin screens** (this plan) | — |
| later | LLM purpose/misuse layer (separate spec) | — |

## Global Constraints

- **Environment:**
  - Frontend commands run in `frontend/` (Node 24, npm 11).
  - `npm test` runs `check:api`, the typecheck, lint and Vitest; `npm run build` must pass.
  - No backend change and no new dependency.
- **"Kodda sabit değer yok" (no hardcoded values):**
  - No page size, limit, interval or URL appears in code. Paging comes from the backend; limits come from `useUiConfig()`.
  - Presentational styling, protocol facts and API enum names are allowed.
- **Text:**
  - Every user-visible text lives in `src/i18n/tr.ts`, in Turkish sentences.
  - **Software terms stay English** (user ruling): class, method, field, package, module, repository, commit, branch, classpath, dependency, library, entry point, annotation, override, project, cron, base URL, bind DN.
  - `src/i18n/terms.test.ts` must keep passing. Its regex rejects sınıf, metot, metod, yapıcı, arayüz, alan okuma, alan yazma, imza, paket, modül, kütüphane, bağımlılık. Do not write "arayüz" even in its UI sense.
  - Backend problem details are shown through `errorMessage()`.
- **Secrets (spec §4.3):**
  - A secret input is never pre-filled.
  - Its value lives only in component state and is sent once in the save request.
  - It is never written to the URL, `localStorage`/`sessionStorage`, the query cache or a notification.
  - "Kayıtlı" / "Boş" comes from `secretSet` / `bindPasswordSet`.
- **Patterns (from Plans 9–11):**
  - Data-first rendering: `data ? content : isError ? <ErrorView/> : <Loading/>`.
  - Anything that navigates is a real link (`Anchor`/`Button component={Link}`).
  - Placeholder (`keepPreviousData`) data is dimmed.
  - `useUrlState` holds filters and paging, with page reset on filter change.
  - Values are rendered through `formatDateTime`, `formatNumber`, `formatRepository` and `enumLabel`; missing values render as `tr.common.none`.
- **Mutations:** every write uses `useMutation` with `onError: notifyError`; on success it invalidates the affected query keys and shows a success notification from `tr`. Destructive actions use `ConfirmButton`:
  - delete;
  - deactivate a user;
  - cancel a run.
- **Tests:**
  - MSW with `onUnhandledRequest: 'error'` (so an unexpected admin call fails a test).
  - `signedIn({ user: { role: 'ADMIN' } })` for admin screens.
  - Assertions through `tr`, except backend-provided data.
- **Running test environment:** the user is using a test WildFly on port 9080 (`/tmp/wildfly-gate/wildfly-41.0.0.Final`) with Oracle container `graphify-wildfly-db` on 1522 and demo data.
  - Never stop, restart or redeploy them. The controller redeploys after the merge, with the user's consent.
  - Never touch the user's WildFly on 8080 or container `fw-batch-oracle`.
- **Staging:** stage paths explicitly (`frontend/...`, `README.md`); never `git add -A` / `git add .` at the root.
- **Commits** end with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V
  ```

## Review Focus

1. **Editing a connection that has a stored token.**
   - Saving without touching the field sends no `secret`, so the token is kept.
   - "Kayıtlı değeri sil" sends `""`.
   - Changing the base URL while a token is stored blocks the save with the re-entry hint.
   - The token never appears in the DOM, the URL or the cache.

   Tests: Task 1 `secretField.test.ts`, Task 2 `scmConnections.test.tsx` "keeps, clears and re-asks for the token".
2. **A USER following a link to an admin page** sees the forbidden notice, and no admin request is sent. Test: Task 1 `requireRole.test.tsx`.
3. **Starting a run while one is running.** The backend's 409 message appears with a link to the running run, and nothing else breaks. Test: Task 2 `startRun.test.tsx` "shows the running run on a conflict".
4. **Demoting or deactivating the last admin.** The backend's 409 message is shown, the row keeps its previous value, and the list is refetched. Test: Task 4 `users.test.tsx` "shows the last-admin rule".
5. **A setting value the backend rejects.** The reason shows under that field and the typed value stays. A saved value refreshes the UI settings (`ui-config`) at once. Test: Task 4 `settings.test.tsx`.

---

## File Structure (all under `frontend/src/` unless noted)

| File | Responsibility |
|---|---|
| `i18n/tr.ts` (modify) | `tr.admin.*` texts, new `tr.enums.*` |
| `auth/RequireRole.tsx`, `auth/session.ts` (modify: `useIsAdmin`) | Role guard |
| `components/SecretField.tsx`, `components/secret.ts`, `components/ConfirmButton.tsx`, `components/Badges.tsx` (modify: `SyncStatusBadge`, `EnabledBadge`) | Shared admin parts |
| `api/notify.ts` (modify: `notifySuccess`), `api/client.ts` (modify: `Problem.runId`) | Notifications, run-conflict property |
| `api/runs.ts` (modify), `features/runs/StartRunForm.tsx`, `features/runs/CancelRunButton.tsx`, `features/repositories/ScanRepositoryButton.tsx` | Start and cancel runs |
| `api/scmConnections.ts`, `features/admin/scm/ScmConnectionsPage.tsx`, `ScmConnectionPage.tsx` | SCM connections |
| `api/artifactRepositories.ts`, `features/admin/artifacts/ArtifactRepositoriesPage.tsx`, `ArtifactRepositoryPage.tsx` | Maven repositories |
| `api/ldap.ts`, `features/admin/ldap/LdapPage.tsx` | LDAP settings |
| `api/users.ts`, `features/admin/users/UsersPage.tsx`, `DirectorySearch.tsx` | Users |
| `api/settings.ts`, `features/admin/settings/SettingsPage.tsx` | Settings |
| `api/impactRules.ts`, `features/admin/rules/EntryPointsPage.tsx`, `ImpactRulesPage.tsx` | Entry-point annotations, impact rules |
| `api/audit.ts`, `features/admin/audit/AuditPage.tsx` | Audit log |
| `routes.tsx`, `layout/navigation.ts` (modify) | Admin routes and menu |
| `README.md` (modify) | Screens list |

---

### Task 1: Admin foundation: role guard, menu, texts, secret field, confirm button

**Files:**
- Create: `frontend/src/auth/RequireRole.tsx`, `frontend/src/components/secret.ts`, `frontend/src/components/SecretField.tsx`, `frontend/src/components/ConfirmButton.tsx`
- Modify: `frontend/src/auth/session.ts`, `frontend/src/api/notify.ts`, `frontend/src/api/client.ts`, `frontend/src/components/Badges.tsx`, `frontend/src/i18n/tr.ts`, `frontend/src/layout/navigation.ts`, `frontend/src/routes.tsx`
- Test: `frontend/src/components/secretField.test.ts`, `frontend/src/auth/requireRole.test.tsx`, `frontend/src/layout/navigation.test.ts` (extend)

**Interfaces:**
- **Consumes:** `useMe`, `Role` (`auth/session.ts`); `NavItem`, `visibleItems`, `isActive`; `AppLayout`'s existing "Yönetim" group; `notifications`.
- **Produces:**
  - **Auth:**
    - `useIsAdmin(): boolean`.
    - `<RequireRole role children />`, which renders `<Alert>` with `tr.admin.forbidden` when the signed-in role differs.
  - **Secrets (`components/secret.ts`):**
    - `type SecretState = { mode: 'keep' } | { mode: 'clear' } | { mode: 'set'; value: string }`, with `KEEP_SECRET`.
    - `secretPayload(state): string | undefined`: `undefined` (key omitted, the backend keeps the secret) for keep, `''` for clear, the value for set.
    - `secretNeedsReentry(stored, targetChanged, state): boolean`.
  - **Components:**
    - `<SecretField label stored state onChange reentry? />`.
    - `<ConfirmButton label message onConfirm loading? color? variant? />`.
  - **Notifications:** `notifySuccess(message)`.
  - **API client:** `Problem.runId?: number`.
  - **Badges:** `<SyncStatusBadge value at? />` and `<EnabledBadge enabled />`.
  - **Texts:** `tr.admin.*` (common texts plus every section below) and `tr.enums.{userSource, settingType, syncStatus, scmType}`.
  - **Menu:** the `NAV_ITEMS` admin entries, each with `role: 'ADMIN'`:
    - `/admin/scm-connections`
    - `/admin/artifact-repositories`
    - `/admin/users`
    - `/admin/ldap`
    - `/admin/settings`
    - `/admin/entry-points`
    - `/admin/impact-rules`
    - `/admin/audit`
  - **Routes:** an `admin` route subtree under `RequireRole role="ADMIN"`. Its pages are placeholders (`NotFoundPage`) until Tasks 2–5 replace them.

- [ ] **Step 1: Write the failing tests**

`frontend/src/components/secretField.test.ts`:

```ts
import { expect, it } from 'vitest';
import { KEEP_SECRET, secretNeedsReentry, secretPayload } from './secret';

it('sends nothing to keep, an empty string to clear and the text to replace', () => {
  expect(secretPayload(KEEP_SECRET)).toBeUndefined();
  expect(secretPayload({ mode: 'clear' })).toBe('');
  expect(secretPayload({ mode: 'set', value: 's3cret' })).toBe('s3cret');
  expect(JSON.stringify({ name: 'x', secret: secretPayload(KEEP_SECRET) })).toBe('{"name":"x"}');
});

it('asks for the secret again only when a stored one would go to a changed target', () => {
  expect(secretNeedsReentry(true, true, KEEP_SECRET)).toBe(true);
  expect(secretNeedsReentry(true, false, KEEP_SECRET)).toBe(false);
  expect(secretNeedsReentry(false, true, KEEP_SECRET)).toBe(false);
  expect(secretNeedsReentry(true, true, { mode: 'set', value: 'new' })).toBe(false);
  expect(secretNeedsReentry(true, true, { mode: 'clear' })).toBe(false);
});
```

`frontend/src/auth/requireRole.test.tsx`:

```tsx
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { tr } from '../i18n/tr';
import { signedIn } from '../test/backend';
import { renderApp } from '../test/renderApp';

describe('admin pages', () => {
  it('a USER following a link sees the forbidden notice and no admin call is made', async () => {
    signedIn();
    renderApp('/admin/settings');

    expect(await screen.findByText(tr.admin.forbidden)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: tr.admin.nav.settings })).not.toBeInTheDocument();
  });

  it('an ADMIN sees the admin menu group', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    renderApp('/');

    expect(await screen.findByText(tr.nav.admin)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: tr.admin.nav.settings })).toHaveAttribute('href', '/admin/settings');
  });
});
```

The first test passes only if no admin endpoint is requested. Any request MSW does not handle fails the test, because `onUnhandledRequest` is `'error'`.

The search page loads `/api/v1/repositories` for its repository select. The second test renders `/`, so add `http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json({ items: [], page: 0, size: 500, total: 0 }))` before `renderApp`. Import `http` and `HttpResponse` from `msw`, and `apiUrl` and `server` from `../test/server`.

Extend `frontend/src/layout/navigation.test.ts`:

```ts
it('lists every admin page for admins only', () => {
  const adminPaths = NAV_ITEMS.filter((item) => item.role === 'ADMIN').map((item) => item.path);
  expect(adminPaths).toEqual(['/admin/scm-connections', '/admin/artifact-repositories', '/admin/users', '/admin/ldap',
    '/admin/settings', '/admin/entry-points', '/admin/impact-rules', '/admin/audit']);
  expect(visibleItems(NAV_ITEMS, 'USER').some((item) => item.role === 'ADMIN')).toBe(false);
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test` (in `frontend/`)
Expected: failures; the modules and texts do not exist yet.

- [ ] **Step 3: Implement**

Add to `tr.ts`, inside `tr`:

```ts
  admin: {
    forbidden: 'Bu sayfa yalnızca yöneticiler içindir.',
    nav: {
      scmConnections: 'SCM bağlantıları',
      artifactRepositories: "Maven repository'leri",
      users: 'Kullanıcılar',
      ldap: 'LDAP',
      settings: 'Ayarlar',
      entryPoints: "Entry point annotation'ları",
      impactRules: 'Etki kuralları',
      audit: 'Denetim kaydı',
    },
    common: {
      save: 'Kaydet',
      cancel: 'Vazgeç',
      delete: 'Sil',
      edit: 'Düzenle',
      create: 'Yeni ekle',
      saved: 'Kaydedildi.',
      deleted: 'Silindi.',
      confirmTitle: 'Emin misiniz?',
      confirm: 'Onayla',
      enabled: 'Etkin',
      disabled: 'Pasif',
      test: 'Bağlantıyı test et',
      testOk: 'Bağlantı başarılı.',
      testUnsaved: 'Test kayıtlı ayarlarla yapılır; önce değişiklikleri kaydedin.',
      lastTest: 'Son test',
      lastSync: 'Son senkronizasyon',
      updated: (by: string, at: string) => `Son değişiklik: ${by}, ${at}`,
      back: 'Listeye dön',
    },
    secret: {
      stored: 'Kayıtlı',
      empty: 'Boş',
      keepHint: 'Boş bırakırsanız kayıtlı değer korunur.',
      clear: 'Kayıtlı değeri sil',
      undoClear: 'Silmekten vazgeç',
      willClear: 'Kaydedince kayıtlı değer silinecek.',
      reenter: (target: string) => `${target} değişti; kayıtlı değer yeni hedefe gönderilmez, lütfen yeniden girin.`,
    },
  },
```

Tasks 2–5 add their own sections under `tr.admin` (`runs`, `scm`, `artifacts`, `ldap`, `users`, `settings`, `entryPoints`, `impactRules`, `audit`).

Add to `tr.enums`:

```ts
    userSource: { LOCAL: 'Yerel', LDAP: 'LDAP' },
    settingType: {
      INT: 'Tam sayı', STRING: 'Metin', BOOL: 'true / false', CRON: 'Cron (6 alan)', LIST: 'Virgülle ayrılmış liste',
      DURATION: 'Süre (ISO-8601, örn. PT10M)',
    },
    syncStatus: {
      SUCCESS: 'Başarılı', AUTH_FAILED: 'Kimlik doğrulama başarısız', FAILED: 'Başarısız',
      DEACTIVATION_SKIPPED: 'Pasifleştirme atlandı',
    },
    scmType: { BITBUCKET_DC: 'Bitbucket Data Center' },
```

`frontend/src/components/secret.ts`:

```ts
/** A secret input in a full update (spec §4.3): keep the stored value, clear it, or replace it. */
export type SecretState = { mode: 'keep' } | { mode: 'clear' } | { mode: 'set'; value: string };

export const KEEP_SECRET: SecretState = { mode: 'keep' };

/** The request value: absent keeps the stored secret, "" clears it, text replaces it. */
export function secretPayload(state: SecretState): string | undefined {
  switch (state.mode) {
    case 'keep':
      return undefined;
    case 'clear':
      return '';
    case 'set':
      return state.value;
  }
}

/**
 * The backend keeps a stored secret only for the same target and answers 400 otherwise; the form says so before
 * sending (the backend still enforces it).
 */
export function secretNeedsReentry(stored: boolean, targetChanged: boolean, state: SecretState): boolean {
  return stored && targetChanged && state.mode === 'keep';
}
```

`frontend/src/components/SecretField.tsx`:

```tsx
import { Badge, Button, Group, PasswordInput, Stack, Text } from '@mantine/core';
import { tr } from '../i18n/tr';
import type { SecretState } from './secret';

/** A token or password: never pre-filled; empty keeps the stored value, "Kayıtlı değeri sil" clears it. */
export function SecretField({ label, stored, state, onChange, reentry }: {
  label: string;
  stored: boolean;
  state: SecretState;
  onChange: (state: SecretState) => void;
  /** The target that changed, when the stored secret must be typed again. */
  reentry?: string;
}) {
  const t = tr.admin.secret;
  return (
    <Stack gap={4}>
      <Group gap="xs">
        <Text size="sm" fw={500}>{label}</Text>
        <Badge size="sm" variant="light" color={stored ? 'green' : 'gray'}>{stored ? t.stored : t.empty}</Badge>
      </Group>
      <PasswordInput
        aria-label={label}
        autoComplete="new-password"
        disabled={state.mode === 'clear'}
        value={state.mode === 'set' ? state.value : ''}
        onChange={(event) => {
          const value = event.currentTarget.value;
          onChange(value ? { mode: 'set', value } : { mode: 'keep' });
        }}
        error={reentry ? t.reenter(reentry) : undefined}
        description={stored && state.mode === 'keep' && !reentry ? t.keepHint : undefined}
      />
      {stored && (
        <Group gap="xs">
          {state.mode === 'clear' ? (
            <>
              <Text size="sm" c="orange">{t.willClear}</Text>
              <Button size="xs" variant="subtle" onClick={() => onChange({ mode: 'keep' })}>{t.undoClear}</Button>
            </>
          ) : (
            <Button size="xs" variant="subtle" color="red" onClick={() => onChange({ mode: 'clear' })}>{t.clear}</Button>
          )}
        </Group>
      )}
    </Stack>
  );
}
```

`frontend/src/components/ConfirmButton.tsx`:

```tsx
import { Button, Group, Modal, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import type { ButtonProps } from '@mantine/core';
import { tr } from '../i18n/tr';

/** A destructive action behind a confirmation dialog (delete, deactivate, cancel a run). */
export function ConfirmButton({ label, message, onConfirm, loading, color = 'red', variant = 'light', disabled }: {
  label: string;
  message: string;
  onConfirm: () => void;
  loading?: boolean;
  color?: ButtonProps['color'];
  variant?: ButtonProps['variant'];
  disabled?: boolean;
}) {
  const [opened, { open, close }] = useDisclosure(false);
  return (
    <>
      <Button color={color} variant={variant} onClick={open} loading={loading} disabled={disabled}>{label}</Button>
      <Modal opened={opened} onClose={close} title={tr.admin.common.confirmTitle}>
        <Text>{message}</Text>
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={close}>{tr.admin.common.cancel}</Button>
          <Button color={color} onClick={() => { close(); onConfirm(); }}>{tr.admin.common.confirm}</Button>
        </Group>
      </Modal>
    </>
  );
}
```

`frontend/src/api/notify.ts`, add:

```ts
/** A finished action: a short green notification (never carries a secret). */
export function notifySuccess(message: string): void {
  notifications.show({ color: 'green', message });
}
```

`frontend/src/api/client.ts`: add to `Problem`:

```ts
  /** The run in progress, sent with a 409 from POST /index/runs (IndexRunConflictException). */
  runId?: number;
```

`frontend/src/components/Badges.tsx`, add:

```tsx
const SYNC_STATUS_COLOR: Record<string, string> = {
  SUCCESS: 'green', AUTH_FAILED: 'red', FAILED: 'red', DEACTIVATION_SKIPPED: 'orange',
};

/** A connection test or sync result; with no result yet it shows "none". */
export function SyncStatusBadge({ value }: { value?: string }) {
  return <Badge variant="light" color={SYNC_STATUS_COLOR[value ?? ''] ?? 'gray'}>{enumLabel(tr.enums.syncStatus, value)}</Badge>;
}

export function EnabledBadge({ enabled }: { enabled?: boolean }) {
  return <Badge variant="dot" color={enabled ? 'green' : 'gray'}>{enabled ? tr.admin.common.enabled : tr.admin.common.disabled}</Badge>;
}
```

`frontend/src/auth/session.ts`, add:

```ts
/** Whether the signed-in user is an admin; the backend enforces the same rule on every admin call. */
export function useIsAdmin(): boolean {
  return useMe().data?.role === 'ADMIN';
}
```

`frontend/src/auth/RequireRole.tsx`:

```tsx
import { Alert } from '@mantine/core';
import type { ReactNode } from 'react';
import { tr } from '../i18n/tr';
import { useMe, type Role } from './session';

/** Pages for one role only (web UI spec §4.2); the backend enforces the same rule on every call. */
export function RequireRole({ role, children }: { role: Role; children: ReactNode }) {
  const me = useMe().data;
  if (me?.role !== role) {
    return <Alert color="red">{tr.admin.forbidden}</Alert>;
  }
  return <>{children}</>;
}
```

`RequireAuth` already guarantees a loaded user here.

`frontend/src/layout/navigation.ts`: append to `NAV_ITEMS`:

```ts
  { path: '/admin/scm-connections', label: tr.admin.nav.scmConnections, role: 'ADMIN' },
  { path: '/admin/artifact-repositories', label: tr.admin.nav.artifactRepositories, role: 'ADMIN' },
  { path: '/admin/users', label: tr.admin.nav.users, role: 'ADMIN' },
  { path: '/admin/ldap', label: tr.admin.nav.ldap, role: 'ADMIN' },
  { path: '/admin/settings', label: tr.admin.nav.settings, role: 'ADMIN' },
  { path: '/admin/entry-points', label: tr.admin.nav.entryPoints, role: 'ADMIN' },
  { path: '/admin/impact-rules', label: tr.admin.nav.impactRules, role: 'ADMIN' },
  { path: '/admin/audit', label: tr.admin.nav.audit, role: 'ADMIN' },
```

`frontend/src/routes.tsx`: inside the authenticated children, before `'*'`:

```tsx
      {
        path: 'admin',
        element: (
          <RequireRole role="ADMIN">
            <Outlet />
          </RequireRole>
        ),
        // Tasks 2–5 replace these placeholders with the real pages
        children: [{ path: '*', element: <NotFoundPage /> }],
      },
```

Import `Outlet` from `react-router` and `RequireRole`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass, including `terms.test.ts`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): admin foundation: role guard, menu, secret field and confirm button" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 2: Index run controls and SCM connections

**Files:**
- Create: `frontend/src/features/runs/StartRunForm.tsx`, `frontend/src/features/runs/CancelRunButton.tsx`, `frontend/src/features/repositories/ScanRepositoryButton.tsx`, `frontend/src/api/scmConnections.ts`, `frontend/src/features/admin/scm/ScmConnectionsPage.tsx`, `frontend/src/features/admin/scm/ScmConnectionPage.tsx`
- Modify: `frontend/src/api/runs.ts`, `frontend/src/features/runs/RunsPage.tsx`, `frontend/src/features/runs/RunPage.tsx`, `frontend/src/features/repositories/RepositoryPage.tsx`, `frontend/src/routes.tsx`, `frontend/src/i18n/tr.ts`
- Test: `frontend/src/features/runs/startRun.test.tsx`, `frontend/src/features/admin/scm/scmConnections.test.tsx`

**Interfaces:**
- **Consumes:**
  - Task 1's parts.
  - Endpoints:
    - `POST /api/v1/index/runs` (`StartRequest` → `StartedRun`; 409 with `runId`)
    - `POST /api/v1/index/runs/{runId}/cancel` (204)
    - `GET`/`POST /api/v1/admin/scm-connections`
    - `GET`/`PUT`/`DELETE /api/v1/admin/scm-connections/{id}`
    - `POST /api/v1/admin/scm-connections/{id}/test`
  - `RepositorySelect` (`features/search/RepositorySelect.tsx`).
- **Produces:**
  - **Run hooks:** `useStartRun()` and `useCancelRun(id)` in `api/runs.ts`. Start success navigates to `/runs/{runId}`; cancel invalidates `['run', id]` and `['runs']`.
  - **Run controls:**
    - `<StartRunForm />`, admin-only, on top of `RunsPage`. It has scope `Select` (ALL / CONNECTION / REPOSITORY), a connection `Select` or `RepositorySelect` for the chosen scope, a force `Switch`, and a start button. On a 409 it shows an inline `Alert` with the detail and, when `runId` is present, a link to `/runs/{runId}`.
    - `<CancelRunButton id />` on `RunPage`, for admins, while the run is `RUNNING` and not yet cancel-requested.
    - `<ScanRepositoryButton id />` on `RepositoryPage`, for admins. It starts a REPOSITORY run, and a 409 is notified.
  - **SCM hooks:** `useScmConnections()`, `useScmConnection(id)`, `useSaveScmConnection(id | null)`, `useDeleteScmConnection(id)`, `useTestScmConnection(id)`.
  - **Routes:** `admin/scm-connections` (list), `admin/scm-connections/new` and `admin/scm-connections/:id` (form).

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/runs/startRun.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

function runsBackend(start: (body: unknown) => Response) {
  signedIn({ user: { role: 'ADMIN' } });
  server.use(
    http.get(apiUrl('/api/v1/index/runs'), () => HttpResponse.json({ items: [], page: 0, size: 50, total: 0 })),
    http.get(apiUrl('/api/v1/admin/scm-connections'), () => HttpResponse.json([{ id: 4, name: 'corp', type: 'BITBUCKET_DC' }])),
    http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json({ items: [{ id: 3, projectKey: 'SHOP', slug: 'api' }], page: 0, size: 500, total: 1 })),
    http.post(apiUrl('/api/v1/index/runs'), async ({ request }) => start(await request.json())),
    http.get(apiUrl('/api/v1/index/runs/12'), () => HttpResponse.json({ run: { id: 12, status: 'RUNNING' }, repositories: [], inProgress: [] })),
  );
}

describe('starting an index run', () => {
  it('starts a full run and opens it', async () => {
    const bodies: unknown[] = [];
    runsBackend((body) => {
      bodies.push(body);
      return HttpResponse.json({ runId: 12 }, { status: 202 });
    });
    const router = renderApp('/runs');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.runs.start }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/runs/12'));
    expect(bodies).toEqual([{ scope: 'ALL', force: false }]);
  });

  it('shows the running run on a conflict', async () => {
    runsBackend(() => HttpResponse.json({ status: 409, detail: 'Index run 7 is in progress', runId: 7 }, { status: 409 }));
    renderApp('/runs');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.runs.start }));

    expect(await screen.findByText('Index run 7 is in progress')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: tr.admin.runs.openRunning })).toHaveAttribute('href', '/runs/7');
  });

  it('is not offered to a USER', async () => {
    signedIn();
    server.use(http.get(apiUrl('/api/v1/index/runs'), () => HttpResponse.json({ items: [], page: 0, size: 50, total: 0 })));
    renderApp('/runs');

    expect(await screen.findByRole('heading', { name: tr.runs.title })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: tr.admin.runs.start })).not.toBeInTheDocument();
  });
});
```

`frontend/src/features/admin/scm/scmConnections.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

const stored = {
  id: 4, name: 'corp', type: 'BITBUCKET_DC', baseUrl: 'https://scm.corp', username: 'svc', secretSet: true,
  includeProjects: ['SHOP'], excludeRepos: [], enabled: true, lastTestStatus: 'SUCCESS',
  lastTestAt: '2026-10-07T09:00:00Z', lastSyncStatus: 'DEACTIVATION_SKIPPED', lastSyncAt: '2026-10-07T09:05:00Z',
  repositoryCount: 12,
};

function scmBackend(puts: unknown[] = []) {
  signedIn({ user: { role: 'ADMIN' } });
  server.use(
    http.get(apiUrl('/api/v1/admin/scm-connections'), () => HttpResponse.json([stored])),
    http.get(apiUrl('/api/v1/admin/scm-connections/4'), () => HttpResponse.json(stored)),
    http.put(apiUrl('/api/v1/admin/scm-connections/4'), async ({ request }) => {
      puts.push(await request.json());
      return HttpResponse.json(stored);
    }),
    http.post(apiUrl('/api/v1/admin/scm-connections/4/test'), () =>
      HttpResponse.json({ status: 502, detail: 'https://***@scm.corp answered 401' }, { status: 502 })),
    http.delete(apiUrl('/api/v1/admin/scm-connections/4'), () =>
      HttpResponse.json({ status: 409, detail: 'SCM connection corp still has repositories; disable it instead' },
        { status: 409 })),
  );
  return puts;
}

describe('SCM connections', () => {
  it('lists connections with their last test and sync', async () => {
    scmBackend();
    renderApp('/admin/scm-connections');

    expect(await screen.findByRole('link', { name: 'corp' })).toHaveAttribute('href', '/admin/scm-connections/4');
    expect(screen.getByText(tr.enums.syncStatus.DEACTIVATION_SKIPPED)).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
  });

  it('keeps, clears and re-asks for the token', async () => {
    const puts = scmBackend();
    renderApp('/admin/scm-connections/4');

    const token = await screen.findByLabelText(tr.admin.scm.fields.secret);
    expect(token).toHaveValue('');
    expect(screen.getByText(tr.admin.secret.stored)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('secret');

    await userEvent.click(screen.getByRole('button', { name: tr.admin.secret.clear }));
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1]).toHaveProperty('secret', '');

    const baseUrl = screen.getByLabelText(tr.admin.scm.fields.baseUrl);
    await userEvent.clear(baseUrl);
    await userEvent.type(baseUrl, 'https://other.corp');
    expect(await screen.findByText(tr.admin.secret.reenter(tr.admin.scm.secretTarget))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: tr.admin.common.save })).toBeDisabled();

    await userEvent.type(token, 'new-token');
    expect(screen.getByRole('button', { name: tr.admin.common.save })).toBeEnabled();
    expect(token).toHaveAttribute('type', 'password');
  });

  it('shows the masked test failure and the delete rule', async () => {
    scmBackend();
    renderApp('/admin/scm-connections/4');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.test }));
    expect(await screen.findByText('https://***@scm.corp answered 401')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.delete }));
    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.confirm }));
    expect(await screen.findByText('SCM connection corp still has repositories; disable it instead')).toBeInTheDocument();
  });
});
```

The test step asserts the masked backend detail as it comes; the UI does not add anything to it. After saving, the form resets to the saved view (Step 3); the second save therefore starts from "keep" again before "clear" is clicked. After the second save, the field is back at "keep", so the base-URL change triggers the re-entry hint.

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test`
Expected: failures; the controls and pages do not exist yet.

- [ ] **Step 3: Implement**

Add to `tr.admin`:

```ts
    runs: {
      start: 'Taramayı başlat',
      startTitle: 'Yeni tarama',
      scope: 'Kapsam',
      connection: 'Bağlantı',
      repository: 'Repo',
      force: 'Değişmemiş repoları da tara (force)',
      openRunning: 'Çalışan taramayı aç',
      cancel: 'Taramayı iptal et',
      cancelConfirm: 'Çalışan tarama iptal edilsin mi? Taranmakta olan repolar bitirilir, yenisi başlamaz.',
      cancelRequested: 'İptal istendi.',
      scanRepository: 'Bu repoyu tara',
    },
    scm: {
      title: 'SCM bağlantıları',
      newTitle: 'Yeni SCM bağlantısı',
      columns: { name: 'Ad', type: 'Tür', baseUrl: 'Base URL', state: 'Durum', repositories: 'Repo', lastTest: 'Son test', lastSync: 'Son senkronizasyon' },
      fields: {
        name: 'Ad', type: 'Tür', baseUrl: 'Base URL', username: 'Kullanıcı adı', secret: 'Token / şifre',
        includeProjects: "Dahil edilen project'ler", excludeRepos: 'Hariç tutulan repolar', enabled: 'Etkin',
      },
      hints: {
        includeProjects: "Boş bırakılırsa erişilebilen tüm project'ler taranır.",
        excludeRepos: 'Örnek: SHOP/legacy-* (Enter ile ekleyin)',
      },
      secretTarget: 'Base URL veya kullanıcı adı',
      syncError: 'Senkronizasyon hatası',
      deleteConfirm: (name: string) => `${name} bağlantısı silinsin mi?`,
    },
```

`frontend/src/api/runs.ts`, add (import `useMutation` and `useQueryClient`; `notifySuccess` and `notifyError` from `./notify`; `tr`; `useNavigate` from `react-router`; `components` from `./schema`):

```ts
export type StartRequest = components['schemas']['StartRequest'];

/** Starts a run and opens it; the caller shows a 409 (another run in progress) itself. */
export function useStartRun() {
  const client = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: (request: StartRequest) => call(api.POST('/api/v1/index/runs', { body: request })),
    onSuccess: (started) => {
      void client.invalidateQueries({ queryKey: ['runs'] });
      if (started?.runId != null) {
        navigate(`/runs/${started.runId}`);
      }
    },
  });
}

export function useCancelRun(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/api/v1/index/runs/{runId}/cancel', { params: { path: { runId: id } } })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['run', id] });
      void client.invalidateQueries({ queryKey: ['runs'] });
      notifySuccess(tr.admin.runs.cancelRequested);
    },
    onError: notifyError,
  });
}
```

`useStartRun` has no `onError`: its form renders the error inline. `ScanRepositoryButton` passes `{ onError: notifyError }` to `mutate`.

`frontend/src/features/runs/StartRunForm.tsx`:

```tsx
import { Alert, Anchor, Button, Card, Group, Select, Stack, Switch, Title } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import { errorMessage } from '../../api/errors';
import { useStartRun, type StartRequest } from '../../api/runs';
import { useScmConnections } from '../../api/scmConnections';
import { tr } from '../../i18n/tr';
import { RepositorySelect } from '../search/RepositorySelect';

type Scope = NonNullable<StartRequest['scope']>;
const SCOPES: Scope[] = ['ALL', 'CONNECTION', 'REPOSITORY'];

/** Admins start a run here (web UI spec §4.2 row 9); a conflict links to the run in progress. */
export function StartRunForm() {
  const [scope, setScope] = useState<Scope>('ALL');
  const [id, setId] = useState<number | undefined>();
  const [force, setForce] = useState(false);
  const start = useStartRun();
  const connections = useScmConnections(scope === 'CONNECTION');
  const conflictRun = start.error instanceof ApiError && start.error.status === 409 ? start.error.problem.runId : undefined;

  return (
    <Card withBorder>
      <Stack>
        <Title order={4}>{tr.admin.runs.startTitle}</Title>
        <Group align="end" grow>
          <Select
            label={tr.admin.runs.scope}
            data={SCOPES.map((value) => ({ value, label: tr.enums.runScope[value] }))}
            value={scope}
            allowDeselect={false}
            onChange={(value) => { setScope((value ?? 'ALL') as Scope); setId(undefined); }}
          />
          {scope === 'CONNECTION' && (
            <Select
              label={tr.admin.runs.connection}
              data={(connections.data ?? []).map((c) => ({ value: String(c.id), label: c.name ?? String(c.id) }))}
              value={id == null ? null : String(id)}
              onChange={(value) => setId(value ? Number(value) : undefined)}
            />
          )}
          {scope === 'REPOSITORY' && (
            <RepositorySelect label={tr.admin.runs.repository} placeholder={tr.admin.runs.repository} value={id}
              onChange={setId} />
          )}
        </Group>
        <Switch label={tr.admin.runs.force} checked={force} onChange={(event) => setForce(event.currentTarget.checked)} />
        {start.error && (
          <Alert color="red">
            {errorMessage(start.error)}
            {conflictRun != null && (
              <>
                {' '}
                <Anchor component={Link} to={`/runs/${conflictRun}`}>{tr.admin.runs.openRunning}</Anchor>
              </>
            )}
          </Alert>
        )}
        <Group>
          <Button
            loading={start.isPending}
            disabled={scope !== 'ALL' && id == null}
            onClick={() => start.mutate(scope === 'ALL' ? { scope, force } : { scope, id, force })}
          >
            {tr.admin.runs.start}
          </Button>
        </Group>
      </Stack>
    </Card>
  );
}
```

In `RunsPage.tsx`, render `{isAdmin && <StartRunForm />}` under the title, with `const isAdmin = useIsAdmin();`.

`frontend/src/features/runs/CancelRunButton.tsx`:

```tsx
import { useCancelRun } from '../../api/runs';
import { ConfirmButton } from '../../components/ConfirmButton';
import { tr } from '../../i18n/tr';

export function CancelRunButton({ id }: { id: number }) {
  const cancel = useCancelRun(id);
  return (
    <ConfirmButton label={tr.admin.runs.cancel} message={tr.admin.runs.cancelConfirm} loading={cancel.isPending}
      onConfirm={() => cancel.mutate()} />
  );
}
```

In `RunPage.tsx`, inside the header `Group`, add `{isAdmin && run?.status === 'RUNNING' && !run.cancelRequested && <CancelRunButton id={id} />}`.

`frontend/src/features/repositories/ScanRepositoryButton.tsx`:

```tsx
import { Button } from '@mantine/core';
import { notifyError } from '../../api/notify';
import { useStartRun } from '../../api/runs';
import { tr } from '../../i18n/tr';

/** Admins re-scan one repository (force), then land on the run. */
export function ScanRepositoryButton({ id }: { id: number }) {
  const start = useStartRun();
  return (
    <Button variant="light" loading={start.isPending}
      onClick={() => start.mutate({ scope: 'REPOSITORY', id, force: true }, { onError: notifyError })}>
      {tr.admin.runs.scanRepository}
    </Button>
  );
}
```

In `RepositoryPage.tsx`, next to the "Grafı göster" button: `{isAdmin && <ScanRepositoryButton id={id} />}`.

`frontend/src/api/scmConnections.ts`:

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import type { components } from './schema';

export type ScmConnectionView = components['schemas']['ScmConnectionView'];
export type ScmConnectionUpdate = components['schemas']['ScmConnectionUpdate'];

const LIST_KEY = ['scm-connections'] as const;

export function useScmConnections(enabled = true) {
  return useQuery({
    queryKey: LIST_KEY,
    enabled,
    queryFn: () => call(api.GET('/api/v1/admin/scm-connections')),
  });
}

export function useScmConnection(id: number | null) {
  return useQuery({
    queryKey: ['scm-connection', id],
    enabled: id != null,
    queryFn: () => call(api.GET('/api/v1/admin/scm-connections/{id}', { params: { path: { id: id! } } })),
  });
}

/** Create (id null) or full update; the secret field follows secretPayload (absent keeps the stored one). */
export function useSaveScmConnection(id: number | null) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ScmConnectionUpdate) => id == null
      ? call(api.POST('/api/v1/admin/scm-connections', { body }))
      : call(api.PUT('/api/v1/admin/scm-connections/{id}', { params: { path: { id } }, body })),
    onSuccess: (saved) => {
      void client.invalidateQueries({ queryKey: LIST_KEY });
      client.setQueryData(['scm-connection', saved?.id], saved);
      notifySuccess(tr.admin.common.saved);
    },
    onError: notifyError,
  });
}

export function useDeleteScmConnection(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.DELETE('/api/v1/admin/scm-connections/{id}', { params: { path: { id } } })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: LIST_KEY });
      notifySuccess(tr.admin.common.deleted);
    },
    onError: notifyError,
  });
}

/** Tests the saved configuration; the result (and the updated last-test fields) are refetched. */
export function useTestScmConnection(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/api/v1/admin/scm-connections/{id}/test', { params: { path: { id } } })),
    onSuccess: () => notifySuccess(tr.admin.common.testOk),
    onError: notifyError,
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['scm-connection', id] });
      void client.invalidateQueries({ queryKey: LIST_KEY });
    },
  });
}
```

If the generated `ScmConnectionUpdate` type marks fields optional, keep it as is. The form always sends every field except `secret` when it is kept.

`frontend/src/features/admin/scm/ScmConnectionsPage.tsx`:
- `Title` `tr.admin.scm.title`, with a `Button component={Link} to="/admin/scm-connections/new"` labelled `tr.admin.common.create`.
- A table with one row per connection:
  - name as `Anchor component={Link} to={`/admin/scm-connections/${id}`}`;
  - `enumLabel(tr.enums.scmType, type)`;
  - `baseUrl`;
  - `<EnabledBadge enabled />`;
  - `formatNumber(repositoryCount)`;
  - last test as `<SyncStatusBadge value={lastTestStatus} />` plus `formatDateTime(lastTestAt)`;
  - last sync as badge, time and, when present, `lastSyncError` in red small text.
- Data-first rendering; an empty list shows `tr.common.empty`.

`frontend/src/features/admin/scm/ScmConnectionPage.tsx`: the route param `id` is `'new'`, or a positive integer (anything else renders `NotFoundPage`). It loads `useScmConnection(id)` for an existing id, renders data-first, and then renders `<ScmConnectionForm key={view?.id ?? 'new'} view={view} />`.

The form:
- **State:** Mantine `useForm` over `{ name, type, baseUrl, username, includeProjects: string[], excludeRepos: string[], enabled }`, initialised from `view` (defaults: `type: 'BITBUCKET_DC'`, `enabled: true`, empty strings and arrays). The secret is a separate `useState<SecretState>(KEEP_SECRET)`.
- **Fields:**
  - `TextInput`s labelled from `tr.admin.scm.fields` (name, baseUrl, username);
  - a `Select` for type over `tr.enums.scmType`;
  - `TagsInput`s for includeProjects and excludeRepos, with the `tr.admin.scm.hints` descriptions;
  - a `Switch` for enabled;
  - `<SecretField label={fields.secret} stored={!!view?.secretSet} state={secret} onChange={setSecret} reentry={needsReentry ? tr.admin.scm.secretTarget : undefined} />`.
- **Re-entry rule:** `targetChanged = view != null && (values.baseUrl !== (view.baseUrl ?? '') || values.username !== (view.username ?? ''))` and `needsReentry = secretNeedsReentry(!!view?.secretSet, targetChanged, secret)`.
- **Buttons:**
  - **Save:** a `type="submit"` button labelled `tr.admin.common.save`, `disabled={needsReentry}`. On submit it calls `save.mutate({ ...values, username: values.username || undefined, secret: secretPayload(secret) })`. On success it resets the secret to `KEEP_SECRET`, resets the form to the saved view (`form.setInitialValues` + `form.reset()`), and navigates a new connection to `/admin/scm-connections/{saved.id}` with `replace: true`.
  - **Test** (existing connections only): `tr.admin.common.test`. It is disabled while `form.isDirty() || secret.mode !== 'keep'`, with the `tr.admin.common.testUnsaved` description shown in that case.
  - **Delete** (existing connections only): a `<ConfirmButton label={tr.admin.common.delete} message={tr.admin.scm.deleteConfirm(name)} />`. On success it navigates to `/admin/scm-connections`.
  - **Back:** a `Button component={Link} to="/admin/scm-connections" variant="subtle"` labelled `tr.admin.common.back`.
- **Error:** `save.error` renders as `<Alert color="red">{errorMessage(save.error)}</Alert>` above the buttons.
- **Footer:** last test and last sync (badge and time), shown for existing connections.

The save button is the form's only submit button. Test and delete are `type="button"`.

`frontend/src/routes.tsx`: replace the admin placeholder children with:

```tsx
        children: [
          { path: 'scm-connections', element: <ScmConnectionsPage /> },
          { path: 'scm-connections/:id', element: <ScmConnectionPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
```

`new` is handled as the `:id` value `'new'`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): start and cancel index runs; manage and test SCM connections" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 3: Maven repositories and LDAP settings

**Files:**
- Create: `frontend/src/api/artifactRepositories.ts`, `frontend/src/features/admin/artifacts/ArtifactRepositoriesPage.tsx`, `ArtifactRepositoryPage.tsx`, `frontend/src/api/ldap.ts`, `frontend/src/features/admin/ldap/LdapPage.tsx`
- Modify: `frontend/src/routes.tsx`, `frontend/src/i18n/tr.ts`
- Test: `frontend/src/features/admin/artifacts/artifactRepositories.test.tsx`, `frontend/src/features/admin/ldap/ldap.test.tsx`

**Interfaces:**
- **Consumes:**
  - Task 1's parts and Task 2's SCM form, as the pattern to mirror.
  - Maven endpoints: `GET`/`POST /api/v1/admin/artifact-repositories`, `GET`/`PUT`/`DELETE /{id}`, `POST /{id}/test`.
  - LDAP endpoints: `GET`/`PUT /api/v1/admin/ldap`, `POST /api/v1/admin/ldap/test` (body `LdapConfigUpdate`, `{ok:true}` or 502/400).
- **Produces:**
  - Maven hooks `useArtifactRepositories`, `useArtifactRepository`, `useSaveArtifactRepository`, `useDeleteArtifactRepository` and `useTestArtifactRepository`. They match the SCM hooks, with the query keys `['artifact-repositories']` and `['artifact-repository', id]`.
  - LDAP hooks `useLdapConfig`, `useSaveLdapConfig` and `useTestLdapConfig`. The test sends the form as typed, plus `bindPassword: secretPayload(secret)`.
  - Routes `admin/artifact-repositories`, `admin/artifact-repositories/:id` (`new` included) and `admin/ldap`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/admin/artifacts/artifactRepositories.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

const nexus = { id: 2, name: 'nexus', url: 'https://nexus.corp/repository/maven-public/', username: 'ci', secretSet: true,
  mirrorOf: '*', sortOrder: 0, enabled: true, lastTestStatus: 'AUTH_FAILED', lastTestAt: '2026-10-07T09:00:00Z' };

describe('Maven repositories', () => {
  it('creates a repository with a password and opens it', async () => {
    const posts: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/artifact-repositories'), () => HttpResponse.json([nexus])),
      http.post(apiUrl('/api/v1/admin/artifact-repositories'), async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ ...nexus, id: 5, name: 'mirror' }, { status: 201 });
      }),
      http.get(apiUrl('/api/v1/admin/artifact-repositories/5'), () => HttpResponse.json({ ...nexus, id: 5, name: 'mirror' })),
    );
    const router = renderApp('/admin/artifact-repositories/new');

    await userEvent.type(await screen.findByLabelText(tr.admin.artifacts.fields.name), 'mirror');
    await userEvent.type(screen.getByLabelText(tr.admin.artifacts.fields.url), 'https://mirror.corp/maven/');
    await userEvent.type(screen.getByLabelText(tr.admin.artifacts.fields.secret), 'pw-1');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/artifact-repositories/5'));
    expect(posts[0]).toMatchObject({ name: 'mirror', url: 'https://mirror.corp/maven/', secret: 'pw-1', sortOrder: 0, enabled: true });
  });

  it('lists repositories with their last test', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(http.get(apiUrl('/api/v1/admin/artifact-repositories'), () => HttpResponse.json([nexus])));
    renderApp('/admin/artifact-repositories');

    expect(await screen.findByRole('link', { name: 'nexus' })).toHaveAttribute('href', '/admin/artifact-repositories/2');
    expect(screen.getByText(tr.enums.syncStatus.AUTH_FAILED)).toBeInTheDocument();
  });
});
```

`frontend/src/features/admin/ldap/ldap.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

const config = { enabled: true, url: 'ldaps://ldap.corp', baseDn: 'dc=corp', userSearchBase: 'ou=people',
  userSearchFilter: '(uid={0})', userQueryFilter: '(|(uid=*{0}*)(cn=*{0}*))', usernameAttr: 'uid',
  displayNameAttr: 'cn', emailAttr: 'mail', bindDn: 'cn=svc,dc=corp', bindPasswordSet: true,
  updatedBy: 'admin', updatedAt: '2026-10-07T09:00:00Z' };

describe('LDAP settings', () => {
  it('tests the form as typed without sending the stored password', async () => {
    const tests: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/ldap'), () => HttpResponse.json(config)),
      http.post(apiUrl('/api/v1/admin/ldap/test'), async ({ request }) => {
        tests.push(await request.json());
        return HttpResponse.json({ ok: true });
      }),
    );
    renderApp('/admin/ldap');

    const userSearchBase = await screen.findByLabelText(tr.admin.ldap.fields.userSearchBase);
    await userEvent.clear(userSearchBase);
    await userEvent.type(userSearchBase, 'ou=staff');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.test }));

    expect(await screen.findByText(tr.admin.common.testOk)).toBeInTheDocument();
    expect(tests[0]).toMatchObject({ userSearchBase: 'ou=staff', url: 'ldaps://ldap.corp' });
    expect(tests[0]).not.toHaveProperty('bindPassword');
  });

  it('asks for the bind password again when the URL changes', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(http.get(apiUrl('/api/v1/admin/ldap'), () => HttpResponse.json(config)));
    renderApp('/admin/ldap');

    const url = await screen.findByLabelText(tr.admin.ldap.fields.url);
    await userEvent.clear(url);
    await userEvent.type(url, 'ldaps://other.corp');

    expect(await screen.findByText(tr.admin.secret.reenter(tr.admin.ldap.secretTarget))).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: tr.admin.common.save })).toBeDisabled());
  });
});
```

The LDAP filter strings contain `{0}`. `userEvent.type` treats `{` as a special key, so the tests do not type them; the values come from the mocked GET.

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test`
Expected: failures; the pages do not exist yet.

- [ ] **Step 3: Implement**

Add to `tr.admin`:

```ts
    artifacts: {
      title: "Maven repository'leri",
      newTitle: 'Yeni Maven repository',
      columns: { name: 'Ad', url: 'URL', mirrorOf: 'Mirror of', order: 'Sıra', state: 'Durum', lastTest: 'Son test' },
      fields: {
        name: 'Ad', url: 'URL', username: 'Kullanıcı adı', secret: 'Şifre', mirrorOf: 'Mirror of',
        sortOrder: 'Sıra', enabled: 'Etkin',
      },
      hints: {
        url: 'http(s):// veya file: URL; kimlik bilgisini URL içine yazmayın.',
        mirrorOf: "Örnek: * (tüm repository'lerin mirror'ı); boş bırakılırsa ek repository olur.",
      },
      secretTarget: 'URL veya kullanıcı adı',
      deleteConfirm: (name: string) => `${name} Maven repository'si silinsin mi?`,
    },
    ldap: {
      title: 'LDAP ayarları',
      fields: {
        enabled: 'LDAP girişi etkin', url: 'URL', baseDn: 'Base DN', userSearchBase: 'User search base',
        userSearchFilter: 'User search filter', userQueryFilter: 'User query filter', usernameAttr: 'Kullanıcı adı attribute',
        displayNameAttr: 'Görünen ad attribute', emailAttr: 'E-posta attribute', bindDn: 'Bind DN', bindPassword: 'Bind şifresi',
      },
      hints: {
        userSearchFilter: '{0} giriş yapan kullanıcı adıyla değiştirilir.',
        userQueryFilter: '{0} yönetim ekranındaki arama metniyle değiştirilir.',
      },
      secretTarget: 'URL veya Bind DN',
      testHint: 'Test, formdaki (kaydedilmemiş) ayarlarla yapılır.',
    },
```

`frontend/src/api/artifactRepositories.ts`: mirror `scmConnections.ts` exactly, with these differences:
- the endpoint paths;
- the types `ArtifactRepositoryView` and `ArtifactRepositoryUpdate`;
- the keys `['artifact-repositories']` and `['artifact-repository', id]`.

`ArtifactRepositoriesPage` lists the repositories ordered as the backend returns them (by sort order). Its columns are `tr.admin.artifacts.columns`, and the name is a link to `/admin/artifact-repositories/{id}`.

`ArtifactRepositoryPage` mirrors `ScmConnectionPage`:
- The fields are name, url (with the hint), username, password (`SecretField`; `reentry` when `url` or `username` changes while `secretSet`), mirrorOf (with the hint), sortOrder (`NumberInput`, `min={0}`, default `0`) and enabled (default true).
- The payload is `{ ...values, username: values.username || undefined, mirrorOf: values.mirrorOf || undefined, secret: secretPayload(secret) }`.
- Test and delete behave as in the SCM form.

`frontend/src/api/ldap.ts`:

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import type { components } from './schema';

export type LdapConfigView = components['schemas']['LdapConfigView'];
export type LdapConfigUpdate = components['schemas']['LdapConfigUpdate'];

const KEY = ['ldap-config'] as const;

export function useLdapConfig() {
  return useQuery({ queryKey: KEY, queryFn: () => call(api.GET('/api/v1/admin/ldap')) });
}

export function useSaveLdapConfig() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: LdapConfigUpdate) => call(api.PUT('/api/v1/admin/ldap', { body })),
    onSuccess: (saved) => {
      client.setQueryData(KEY, saved);
      notifySuccess(tr.admin.common.saved);
    },
    onError: notifyError,
  });
}

/** Tests the form as typed (not saved); a missing password is merged from the stored one by the backend. */
export function useTestLdapConfig() {
  return useMutation({
    mutationFn: (body: LdapConfigUpdate) => call(api.POST('/api/v1/admin/ldap/test', { body })),
    onSuccess: () => notifySuccess(tr.admin.common.testOk),
    onError: notifyError,
  });
}
```

`frontend/src/features/admin/ldap/LdapPage.tsx`: loads `useLdapConfig()` data-first and renders `<LdapForm key={view.updatedAt ?? 'none'} view={view} />`.
- **Fields:**
  - the enabled `Switch`;
  - a `TextInput` for each text field, labelled from `tr.admin.ldap.fields`, with the `hints` as descriptions for the two filters;
  - `<SecretField label={fields.bindPassword} stored={!!view.bindPasswordSet} … reentry={needsReentry ? tr.admin.ldap.secretTarget : undefined} />`, where `targetChanged = url or bindDn differs from view`.
- **Payload:** `{ ...values, bindPassword: secretPayload(secret) }`.
  - Save → `useSaveLdapConfig`, disabled when `needsReentry`. On success it resets the secret to keep.
  - Test → `useTestLdapConfig().mutate(payload)`, also disabled when `needsReentry`, with `tr.admin.ldap.testHint` as text beside it.
- **Footer:** `tr.admin.common.updated(view.updatedBy ?? tr.common.none, formatDateTime(view.updatedAt))`.
- **Error:** `save.error` in an `Alert`.

`frontend/src/routes.tsx`: add the admin children:

```tsx
          { path: 'artifact-repositories', element: <ArtifactRepositoriesPage /> },
          { path: 'artifact-repositories/:id', element: <ArtifactRepositoryPage /> },
          { path: 'ldap', element: <LdapPage /> },
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): manage Maven repositories and LDAP settings" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 4: Users and settings

**Files:**
- Create: `frontend/src/api/users.ts`, `frontend/src/features/admin/users/UsersPage.tsx`, `DirectorySearch.tsx`, `frontend/src/api/settings.ts`, `frontend/src/features/admin/settings/SettingsPage.tsx`
- Modify: `frontend/src/routes.tsx`, `frontend/src/i18n/tr.ts`
- Test: `frontend/src/features/admin/users/users.test.tsx`, `frontend/src/features/admin/settings/settings.test.tsx`

**Interfaces:**
- **Consumes:**
  - User endpoints:
    - `GET /api/v1/admin/users?q&page` → `PageAppUser`
    - `POST /api/v1/admin/users` (`Registration`) → `AppUser`
    - `PUT /api/v1/admin/users/{id}/role` (`RoleChange`)
    - `PUT /api/v1/admin/users/{id}/active` (`ActiveChange`)
    - `GET /api/v1/admin/ldap/users?q` → `DirectoryUserView[]`
  - Setting endpoints: `GET /api/v1/admin/settings` → `Setting[]`, `PUT /api/v1/admin/settings/{key}` (`ValueChange`) → `Setting`.
- **Produces:**
  - **User hooks:** `useUsers(q, page)`, `useRegisterUser()`, `useChangeRole()`, `useChangeActive()` and `useDirectorySearch(q)` (enabled when `q` is non-blank). Role and active mutations invalidate `['users']`, also on error, so a refused change shows the server's truth again.
  - **Setting hooks:** `useSettings()` and `useSaveSetting(key)`. On success the save invalidates `['settings']` and `['ui-config']`. Rows render the error under the field themselves, so the save has no global `onError`.
  - **Routes:** `admin/users` (`?q&page`) and `admin/settings` (`?q` client-side filter on key or description).

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/admin/users/users.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

const admin = { id: 1, username: 'admin', source: 'LOCAL', displayName: 'Yönetici', role: 'ADMIN', active: true };
const ayse = { id: 2, username: 'ayse', source: 'LDAP', displayName: 'Ayşe', role: 'USER', active: true };

function usersBackend() {
  const registrations: unknown[] = [];
  signedIn({ user: { role: 'ADMIN', username: 'admin' } });
  server.use(
    http.get(apiUrl('/api/v1/admin/users'), () => HttpResponse.json({ items: [admin, ayse], page: 0, size: 50, total: 2 })),
    http.put(apiUrl('/api/v1/admin/users/1/active'), () =>
      HttpResponse.json({ status: 409, detail: 'admin is the last active admin' }, { status: 409 })),
    http.get(apiUrl('/api/v1/admin/ldap/users'), () => HttpResponse.json([
      { username: 'ayse', displayName: 'Ayşe', appUserId: 2 },
      { username: 'mehmet', displayName: 'Mehmet', email: 'mehmet@corp' },
    ])),
    http.post(apiUrl('/api/v1/admin/users'), async ({ request }) => {
      registrations.push(await request.json());
      return HttpResponse.json({ id: 3, username: 'mehmet', source: 'LDAP', role: 'USER', active: true }, { status: 201 });
    }),
  );
  return registrations;
}

describe('users', () => {
  it('shows the last-admin rule', async () => {
    usersBackend();
    renderApp('/admin/users');

    const row = (await screen.findByText('admin')).closest('tr')!;
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.users.deactivate }));
    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.confirm }));

    expect(await screen.findByText('admin is the last active admin')).toBeInTheDocument();
    expect(within(row).getByText(tr.admin.common.enabled)).toBeInTheDocument();
  });

  it('adds a user found in the directory', async () => {
    const registrations = usersBackend();
    renderApp('/admin/users');

    await userEvent.type(await screen.findByLabelText(tr.admin.users.directoryQuery), 'meh');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.users.directorySearch }));

    const row = (await screen.findByText('mehmet')).closest('tr')!;
    expect(within((await screen.findAllByText('ayse')).at(-1)!.closest('tr')!).getByText(tr.admin.users.registered))
      .toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.users.add }));

    await waitFor(() => expect(registrations).toEqual([{ username: 'mehmet', role: 'USER' }]));
  });
});
```

`frontend/src/features/admin/settings/settings.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

const settings = [
  { key: 'graph.max_nodes', value: '500', type: 'INT', description: 'Repo grafında azami düğüm', minValue: 10, maxValue: 5000,
    updatedBy: 'admin', updatedAt: '2026-10-07T09:00:00Z' },
  { key: 'index.cron', value: '0 0 2 * * *', type: 'CRON', description: 'Tarama zamanı' },
];

describe('settings', () => {
  it('shows a rejected value under its field and keeps it', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/settings'), () => HttpResponse.json(settings)),
      http.put(apiUrl('/api/v1/admin/settings/index.cron'), () =>
        HttpResponse.json({ status: 400, detail: 'index.cron: not a cron expression with 6 fields' }, { status: 400 })),
    );
    renderApp('/admin/settings');

    const row = (await screen.findByText('index.cron')).closest('tr')!;
    const input = within(row).getByRole('textbox');
    await userEvent.clear(input);
    await userEvent.type(input, 'nope');
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.common.save }));

    expect(await within(row).findByText('index.cron: not a cron expression with 6 fields')).toBeInTheDocument();
    expect(input).toHaveValue('nope');
  });

  it('a saved value refreshes the UI settings', async () => {
    let uiConfigCalls = 0;
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/ui-config'), () => {
        uiConfigCalls++;
        return HttpResponse.json({ pageDefaultSize: 50, pageMaxSize: 500, graphMaxNodes: 800, impactDefaultDepth: 3,
          impactMaxDepth: 10, pollIntervalMillis: 20 });
      }),
      http.get(apiUrl('/api/v1/admin/settings'), () => HttpResponse.json(settings)),
      http.put(apiUrl('/api/v1/admin/settings/graph.max_nodes'), () =>
        HttpResponse.json({ ...settings[0], value: '800' })),
    );
    renderApp('/admin/settings');

    const row = (await screen.findByText('graph.max_nodes')).closest('tr')!;
    expect(within(row).getByText(tr.admin.settings.range('10', '5000'))).toBeInTheDocument();
    const input = within(row).getByRole('textbox');
    await userEvent.clear(input);
    await userEvent.type(input, '800');
    const before = uiConfigCalls;
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.common.save }));

    await waitFor(() => expect(uiConfigCalls).toBeGreaterThan(before));
    expect(await screen.findByText(tr.admin.common.saved)).toBeInTheDocument();
  });
});
```

The second test's `server.use` handler for `/ui-config` replaces the one from `signedIn`. MSW prefers the most recently added handler.

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test`
Expected: failures; the pages do not exist yet.

- [ ] **Step 3: Implement**

Add to `tr.admin`:

```ts
    users: {
      title: 'Kullanıcılar',
      query: 'Kullanıcı adı veya ad',
      columns: { username: 'Kullanıcı adı', name: 'Ad', source: 'Kaynak', role: 'Rol', state: 'Durum', lastLogin: 'Son giriş', granted: 'Rolü veren' },
      deactivate: 'Pasifleştir',
      activate: 'Etkinleştir',
      deactivateConfirm: (name: string) => `${name} pasifleştirilsin mi? Açık oturumları kapanır.`,
      roleChanged: 'Rol değişti.',
      directory: 'LDAP dizininden ekle',
      directoryQuery: 'Dizinde ara',
      directorySearch: 'Dizinde ara',
      registered: 'Kayıtlı',
      add: 'Ekle',
      added: 'Kullanıcı eklendi.',
    },
    settings: {
      title: 'Ayarlar',
      filter: 'Anahtar veya açıklama',
      columns: { key: 'Anahtar', value: 'Değer', type: 'Tür', description: 'Açıklama', updated: 'Son değişiklik' },
      range: (min: string, max: string) => `Aralık: ${min} – ${max}`,
      notInteger: 'Bir tam sayı girin.',
      notBool: 'true veya false girin.',
    },
```

`frontend/src/api/users.ts`:

```ts
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import type { components } from './schema';

export type AppUser = components['schemas']['AppUser'];
export type UserRole = NonNullable<AppUser['role']>;

const USERS = ['users'] as const;

export function useUsers(q: string, page: number) {
  return useQuery({
    queryKey: [...USERS, q, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/admin/users', { params: { query: { q: q || undefined, page } } })),
  });
}

/** Role and active changes refetch the list even when refused, so the row shows the server's truth again. */
function useUserChange<T>(change: (args: T) => Promise<unknown>, done: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: change,
    onSuccess: () => notifySuccess(done),
    onError: notifyError,
    onSettled: () => void client.invalidateQueries({ queryKey: USERS }),
  });
}

export function useChangeRole() {
  return useUserChange(({ id, role }: { id: number; role: UserRole }) =>
    call(api.PUT('/api/v1/admin/users/{id}/role', { params: { path: { id } }, body: { role } })), tr.admin.users.roleChanged);
}

export function useChangeActive() {
  return useUserChange(({ id, active }: { id: number; active: boolean }) =>
    call(api.PUT('/api/v1/admin/users/{id}/active', { params: { path: { id } }, body: { active } })), tr.admin.common.saved);
}

export function useDirectorySearch(q: string) {
  return useQuery({
    queryKey: ['directory', q],
    enabled: q.trim() !== '',
    queryFn: () => call(api.GET('/api/v1/admin/ldap/users', { params: { query: { q: q.trim() } } })),
  });
}

export function useRegisterUser() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { username: string; role: UserRole }) => call(api.POST('/api/v1/admin/users', { body })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: USERS });
      void client.invalidateQueries({ queryKey: ['directory'] });
      notifySuccess(tr.admin.users.added);
    },
    onError: notifyError,
  });
}
```

`frontend/src/features/admin/users/UsersPage.tsx`:
- **Filter:** a query form (submit writes `q` through `useUrlState`, keyed by `q` like the search form).
- **Table** (data-first, dimmed while placeholder):
  - username, displayName;
  - `enumLabel(tr.enums.userSource, source)`;
  - role as a `Select`, `aria-label={tr.admin.users.columns.role}`, over `tr.header.roles` (`ADMIN`/`USER`). Its `value` is the row's `role` and its `onChange` calls `changeRole.mutate({ id, role })`. The value is always the server's, so a refused change snaps back after the refetch.
  - state: `<EnabledBadge enabled={active} />` plus either `<ConfirmButton label={tr.admin.users.deactivate} message={tr.admin.users.deactivateConfirm(username)} onConfirm={() => changeActive.mutate({ id, active: false })} />`, or a plain button labelled `tr.admin.users.activate` that calls `changeActive.mutate({ id, active: true })`;
  - `formatDateTime(lastLoginAt)`;
  - `roleGrantedBy ?? tr.common.none`.
- **Paging:** `Pager`.
- **Directory:** `<DirectorySearch />` in a `Card` below the table.

`frontend/src/features/admin/users/DirectorySearch.tsx`:
- a `Title` `tr.admin.users.directory`;
- a `TextInput` labelled `tr.admin.users.directoryQuery` (local state), with a button labelled `tr.admin.users.directorySearch` that sets the submitted query;
- `useDirectorySearch(submitted)`, with `ErrorView` on error (e.g. LDAP disabled → backend detail) and `tr.common.empty` when there are no results;
- a table with username, displayName and email. When `appUserId != null` the last cell is a badge `tr.admin.users.registered`. Otherwise it is a role `Select` (default `'USER'`, local state per row) and a button labelled `tr.admin.users.add` that calls `register.mutate({ username, role })`.

`frontend/src/api/settings.ts`:

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifySuccess } from './notify';
import type { components } from './schema';

export type Setting = components['schemas']['Setting'];

const KEY = ['settings'] as const;

export function useSettings() {
  return useQuery({ queryKey: KEY, queryFn: () => call(api.GET('/api/v1/admin/settings')) });
}

/** Saves one setting; the row shows a refusal under its field, and a saved value refreshes the UI settings. */
export function useSaveSetting(key: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (value: string) => call(api.PUT('/api/v1/admin/settings/{key}', { params: { path: { key } }, body: { value } })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: KEY });
      void client.invalidateQueries({ queryKey: ['ui-config'] });
      notifySuccess(tr.admin.common.saved);
    },
  });
}
```

`frontend/src/features/admin/settings/SettingsPage.tsx`:
- **Filter:** a `TextInput` labelled `tr.admin.settings.filter`, stored as `q` through `useUrlState`, matched case-insensitively against key or description.
- **Table:** one `<SettingRow key={`${setting.key}-${setting.updatedAt ?? ''}`} setting={setting} />` per setting. Keying on `updatedAt` resets a row's draft after a successful save.
- **`SettingRow`:**
  - **State:** a local `draft` (initialised from `setting.value`) and `const save = useSaveSetting(setting.key)`.
  - **Format hint:** `formatHint` is `tr.admin.settings.notInteger` for INT values that do not match `/^-?\d+$/`, `tr.admin.settings.notBool` for BOOL values other than `true`/`false`, else null. The backend validates everything else.
  - **Error text:** `formatHint ?? (save.error ? errorMessage(save.error) : null)`, passed as the `TextInput`'s `error`, so it renders under that field.
  - **Cells:**
    - key (`Code`);
    - the `TextInput` (`aria-label={setting.key}`);
    - `enumLabel(tr.enums.settingType, type)` with `tr.admin.settings.range(String(minValue ?? tr.common.none), String(maxValue ?? tr.common.none))` under it when either bound exists;
    - description;
    - `tr.admin.common.updated(updatedBy ?? none, formatDateTime(updatedAt))`;
    - a save button, disabled unless `draft !== setting.value && !formatHint`, with `loading={save.isPending}`, that calls `save.mutate(draft)`.

  The bounds are shown raw (`String(...)`), not through `formatNumber`. They are the values an admin types back, and `formatNumber(5000)` would print `5.000` in `tr-TR`; the range test expects `range('10', '5000')`.

`frontend/src/routes.tsx`: add the admin children `{ path: 'users', element: <UsersPage /> }` and `{ path: 'settings', element: <SettingsPage /> }`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npm run build`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): manage users from the directory and edit settings" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 5: Entry-point annotations, impact rules, audit log and README

**Files:**
- Create: `frontend/src/api/impactRules.ts`, `frontend/src/features/admin/rules/EntryPointsPage.tsx`, `frontend/src/features/admin/rules/ImpactRulesPage.tsx`, `frontend/src/api/audit.ts`, `frontend/src/features/admin/audit/AuditPage.tsx`
- Modify: `frontend/src/routes.tsx`, `frontend/src/i18n/tr.ts`, `README.md`
- Test: `frontend/src/features/admin/rules/rules.test.tsx`, `frontend/src/features/admin/audit/audit.test.tsx`

**Interfaces:**
- **Consumes:**
  - Entry-point endpoints: `GET`/`POST /api/v1/admin/entry-point-annotations`, `PUT`/`DELETE /{id}` (`AnnotationChange`: `annotationFqn`, `label`, `enabled`).
  - Impact-rule endpoints: `GET /api/v1/admin/impact-rules`, `PUT /{kind}` (`RuleChange`: `propagates`, `shownAtLevel1`).
  - Audit endpoint: `GET /api/v1/admin/audit?actor&action&from&to&page` → `PageAuditEntry`, where `from`/`to` are ISO instants.
- **Produces:**
  - **Hooks:** `useEntryPoints`, `useSaveEntryPoint(id | null)`, `useDeleteEntryPoint`, `useImpactRules`, `useSaveImpactRule` and `useAudit(filters, page)`.
  - **Routes:** `admin/entry-points`, `admin/impact-rules` and `admin/audit` (`?actor&action&from&to&page`).
  - **README:** the admin screens list.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/admin/rules/rules.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

describe('entry point annotations and impact rules', () => {
  it('adds an annotation and disables another', async () => {
    const posts: unknown[] = [];
    const puts: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/entry-point-annotations'), () => HttpResponse.json([
        { id: 1, annotationFqn: 'org.springframework.web.bind.annotation.GetMapping', label: 'HTTP GET', enabled: true },
      ])),
      http.post(apiUrl('/api/v1/admin/entry-point-annotations'), async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ id: 2, annotationFqn: 'com.corp.Job', label: 'Batch', enabled: true }, { status: 201 });
      }),
      http.put(apiUrl('/api/v1/admin/entry-point-annotations/1'), async ({ request }) => {
        puts.push(await request.json());
        return HttpResponse.json({ id: 1, annotationFqn: 'org.springframework.web.bind.annotation.GetMapping', label: 'HTTP GET', enabled: false });
      }),
    );
    renderApp('/admin/entry-points');

    const row = (await screen.findByText('org.springframework.web.bind.annotation.GetMapping')).closest('tr')!;
    await userEvent.click(within(row).getByRole('switch'));
    await waitFor(() => expect(puts).toEqual([{ annotationFqn: 'org.springframework.web.bind.annotation.GetMapping', label: 'HTTP GET', enabled: false }]));

    await userEvent.type(screen.getByLabelText(tr.admin.entryPoints.fields.annotationFqn), 'com.corp.Job');
    await userEvent.type(screen.getByLabelText(tr.admin.entryPoints.fields.label), 'Batch');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.create }));
    await waitFor(() => expect(posts).toEqual([{ annotationFqn: 'com.corp.Job', label: 'Batch', enabled: true }]));
  });

  it('switches whether a usage kind propagates', async () => {
    const puts: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/impact-rules'), () => HttpResponse.json([{ kind: 'CALL', propagates: true, shownAtLevel1: true }])),
      http.put(apiUrl('/api/v1/admin/impact-rules/CALL'), async ({ request }) => {
        puts.push(await request.json());
        return HttpResponse.json({ kind: 'CALL', propagates: false, shownAtLevel1: true });
      }),
    );
    renderApp('/admin/impact-rules');

    const row = (await screen.findByText(tr.enums.usageKind.CALL)).closest('tr')!;
    await userEvent.click(within(row).getByRole('switch', { name: tr.admin.impactRules.columns.propagates }));

    await waitFor(() => expect(puts).toEqual([{ propagates: false, shownAtLevel1: true }]));
  });
});
```

`frontend/src/features/admin/audit/audit.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

it('filters the audit log through the URL and the API', async () => {
  const seen: URL[] = [];
  signedIn({ user: { role: 'ADMIN' } });
  server.use(http.get(apiUrl('/api/v1/admin/audit'), ({ request }) => {
    seen.push(new URL(request.url));
    return HttpResponse.json({ items: [{ id: 1, actor: 'admin', action: 'SETTING_CHANGED', target: 'graph.max_nodes',
      details: '500 → 800', at: '2026-10-07T09:00:00Z' }], page: 0, size: 50, total: 1 });
  }));
  const router = renderApp('/admin/audit');

  expect(await screen.findByText('SETTING_CHANGED')).toBeInTheDocument();
  await userEvent.type(screen.getByLabelText(tr.admin.audit.actor), 'admin');
  await userEvent.click(screen.getByRole('button', { name: tr.common.apply }));

  await waitFor(() => expect(router.state.location.search).toBe('?actor=admin'));
  await waitFor(() => expect(seen.at(-1)?.searchParams.get('actor')).toBe('admin'));
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test`
Expected: failures; the pages do not exist yet.

- [ ] **Step 3: Implement**

Add to `tr.admin`:

```ts
    entryPoints: {
      title: "Entry point annotation'ları",
      intro: "Bu annotation'ları taşıyan method'lar etki analizinde entry point olarak gösterilir.",
      columns: { annotationFqn: 'Annotation', label: 'Etiket', enabled: 'Etkin' },
      fields: { annotationFqn: 'Annotation (tam ad)', label: 'Etiket' },
      deleteConfirm: (fqn: string) => `${fqn} silinsin mi?`,
    },
    impactRules: {
      title: 'Etki kuralları',
      intro: 'Her kullanım türü için etkinin bir sonraki seviyeye yayılıp yayılmayacağı ve seviye 1\'de gösterilip gösterilmeyeceği.',
      columns: { kind: 'Kullanım türü', propagates: 'Yayılır', shownAtLevel1: "Seviye 1'de göster" },
    },
    audit: {
      title: 'Denetim kaydı',
      actor: 'Kullanıcı',
      action: 'İşlem',
      from: 'Başlangıç',
      to: 'Bitiş',
      columns: { at: 'Zaman', actor: 'Kullanıcı', action: 'İşlem', target: 'Hedef', details: 'Ayrıntı' },
    },
```

`frontend/src/api/impactRules.ts`: hooks over the two endpoint sets, built in the same shape as `scmConnections.ts`:
- **Keys:** `['entry-points']` and `['impact-rules']`.
- **`useSaveEntryPoint(id | null)`:** POST for a null id, PUT otherwise.
- **`useDeleteEntryPoint`:** takes the id as its mutation variable.
- **`useSaveImpactRule`:** takes `{ kind, propagates, shownAtLevel1 }` and PUTs to `/{kind}` with body `{ propagates, shownAtLevel1 }`.
- **Every write:** invalidates its list key, notifies success with `tr.admin.common.saved` or `.deleted`, and uses `onError: notifyError`.

`EntryPointsPage`:
- `Title` and the `intro` text.
- A table with one row per annotation:
  - the fqn in `Code`;
  - the label;
  - a `Switch` (`aria-label={tr.admin.entryPoints.columns.enabled}`) whose change calls save with `{ annotationFqn, label, enabled: next }`;
  - an edit button that opens a small `Modal` form with fqn and label, which saves through PUT;
  - a `ConfirmButton` delete.
- An add form below the table: fqn and label `TextInput`s labelled from `fields`, and a button labelled `tr.admin.common.create` that posts `{ annotationFqn, label, enabled: true }` and clears the inputs on success.
- A 409 (duplicate) or 400 (bad name) is notified with the backend's text.

`ImpactRulesPage`:
- `Title` and `intro`.
- A table with one row per rule:
  - `enumLabel(tr.enums.usageKind, kind)`;
  - two `Switch`es with `aria-label`s from `columns`. Each calls `useSaveImpactRule().mutate({ kind, propagates, shownAtLevel1 })`, with the switched flag flipped.

`frontend/src/api/audit.ts`:

```ts
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, call } from './client';

export interface AuditFilters {
  actor?: string;
  action?: string;
  /** ISO-8601 instants, as the backend expects. */
  from?: string;
  to?: string;
}

export function useAudit(filters: AuditFilters, page: number) {
  return useQuery({
    queryKey: ['audit', filters, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/admin/audit', { params: { query: { ...filters, page } } })),
  });
}
```

`AuditPage`:
- **Filters:** read from `useUrlState` (`actor`, `action`, `from`, `to`). Blank values become `undefined`.
- **Filter form:** keyed by the URL search string, with local state for:
  - `actor` and `action` `TextInput`s labelled from `tr.admin.audit`;
  - two native `datetime-local` inputs (`TextInput type="datetime-local"`), labelled `from` and `to`.

  Their values convert with `new Date(local).toISOString()` on submit, and back with `toLocal(iso)`, a small helper that formats an instant as `YYYY-MM-DDTHH:mm` in local time for the input. Submitting with `tr.common.apply` writes all four through `update`, which resets the page.
- **Table:** `formatDateTime(at)`, actor, action (`Code`), target and details. It is dimmed while placeholder, followed by `Pager`.

`frontend/src/routes.tsx`: add the admin children `entry-points`, `impact-rules` and `audit` with their pages.

`README.md`: under `## Web UI` → Screens, add an "Admin (role ADMIN)" list with one line each:
- start and cancel runs;
- SCM connections and Maven repositories, including a test;
- users from LDAP;
- LDAP;
- settings;
- entry-point annotations;
- impact rules;
- audit log.

Add one sentence on secrets: never shown or filled; empty keeps; "Kayıtlı değeri sil" clears; re-entry when the target changes.

- [ ] **Step 4: Run the tests and the build**

Run (in `frontend/`): `npm test && npm run build`
Expected: all pass.

Run from the repository root: `./mvnw -q package -DskipTests -Dfrontend.node.downloadRoot=https://nodejs.org/dist/ -Dfrontend.npm.registry=https://registry.npmjs.org` (with `JAVA_HOME=/opt/homebrew/opt/openjdk@25`).
Expected: the WAR builds.

**Do not deploy it.** The user's test WildFly on 9080 (and Oracle `graphify-wildfly-db` on 1522) stays as it is. The controller redeploys after the merge, with the user's consent. Never touch the user's WildFly on 8080 or `fw-batch-oracle`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src README.md
git commit -m "feat(frontend): entry-point annotations, impact rules and the audit log" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```
