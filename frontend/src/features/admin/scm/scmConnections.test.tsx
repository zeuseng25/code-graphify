import { cleanup, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

const stored = {
  id: 4, name: 'corp', type: 'BITBUCKET_DC', baseUrl: 'https://scm.corp', username: 'svc', secretSet: true,
  includeProjects: ['SHOP'], excludeRepos: [], repositoryUrls: [], enabled: true, lastTestStatus: 'SUCCESS',
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

// the switch's accessible name also carries its description
const ownLabel = (name: string) => name.startsWith(tr.admin.scm.fields.includeOwnRepositories);

describe('repo connections', () => {
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

  it('is called Repo bağlantıları in the menu and the title', async () => {
    scmBackend();
    renderApp('/admin/scm-connections');

    expect(await screen.findByRole('link', { name: tr.admin.nav.scmConnections })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: tr.admin.scm.title })).toBeInTheDocument();
    expect(tr.admin.nav.scmConnections).toBe('Repo bağlantıları'); // rename guard
  });

  it('sends only the fields of the chosen type', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    const posts: Array<Record<string, unknown>> = [];
    const created = { ...stored, id: 9, type: 'GIT', includeProjects: [], repositoryUrls: [] };
    server.use(
      http.post(apiUrl('/api/v1/admin/scm-connections'), async ({ request }) => {
        posts.push(await request.json() as Record<string, unknown>);
        return HttpResponse.json(created);
      }),
      http.get(apiUrl('/api/v1/admin/scm-connections/9'), () => HttpResponse.json(created)),
    );
    const pick = async (type: string) => {
      await userEvent.click(screen.getByRole('combobox', { name: tr.admin.scm.fields.type }));
      await userEvent.click(await screen.findByRole('option', { name: type, hidden: true }));
    };
    const start = async () => {
      renderApp('/admin/scm-connections/new');
      await userEvent.type(await screen.findByLabelText(tr.admin.scm.fields.name), 'repos');
      await userEvent.type(screen.getByLabelText(tr.admin.scm.fields.baseUrl), 'https://git.corp');
      await userEvent.type(screen.getByLabelText(tr.admin.scm.fields.secret), 'tok');
      await pick(tr.enums.scmType.GITHUB);
      await userEvent.type(await screen.findByRole('combobox', { name: tr.admin.scm.fields.organizations }), 'shop{Enter}');
    };

    await start();
    await pick(tr.enums.scmType.GIT);
    await userEvent.type(await screen.findByLabelText(tr.admin.scm.fields.repositoryUrls),
      'https://git.corp/a.git{Enter}https://git.corp/b.git');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toMatchObject({
      type: 'GIT', includeProjects: [], excludeRepos: [],
      repositoryUrls: ['https://git.corp/a.git', 'https://git.corp/b.git'],
    });

    cleanup();
    await start();
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[1]).toMatchObject({ type: 'GITHUB', includeProjects: ['shop'], repositoryUrls: [] });
  });

  it("saves a GitHub connection that lists only the token owner's repositories", async () => {
    signedIn({ user: { role: 'ADMIN' } });
    const posts: Array<Record<string, unknown>> = [];
    const created = { ...stored, id: 9, type: 'GITHUB', includeProjects: [], repositoryUrls: [], includeOwnRepositories: true };
    server.use(
      http.post(apiUrl('/api/v1/admin/scm-connections'), async ({ request }) => {
        posts.push(await request.json() as Record<string, unknown>);
        return HttpResponse.json(created);
      }),
      http.get(apiUrl('/api/v1/admin/scm-connections/9'), () => HttpResponse.json(created)),
    );
    const pick = async (type: string) => {
      await userEvent.click(screen.getByRole('combobox', { name: tr.admin.scm.fields.type }));
      await userEvent.click(await screen.findByRole('option', { name: type, hidden: true }));
    };
    renderApp('/admin/scm-connections/new');
    await userEvent.type(await screen.findByLabelText(tr.admin.scm.fields.name), 'own');
    await userEvent.type(screen.getByLabelText(tr.admin.scm.fields.baseUrl), 'https://api.github.com');
    await userEvent.type(screen.getByLabelText(tr.admin.scm.fields.secret), 'tok');
    await pick(tr.enums.scmType.GITHUB);

    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    expect(await screen.findByText(tr.admin.scm.githubScope)).toBeInTheDocument();
    expect(posts).toHaveLength(0);

    await userEvent.click(screen.getByRole('switch', { name: ownLabel }));
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toMatchObject({ type: 'GITHUB', includeProjects: [], includeOwnRepositories: true });
  });

  it('shows the own-repositories switch only for GitHub and clears it on a type change', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    const pick = async (type: string) => {
      await userEvent.click(screen.getByRole('combobox', { name: tr.admin.scm.fields.type }));
      await userEvent.click(await screen.findByRole('option', { name: type, hidden: true }));
    };
    renderApp('/admin/scm-connections/new');
    await screen.findByLabelText(tr.admin.scm.fields.name);
    expect(screen.queryByRole('switch', { name: ownLabel })).not.toBeInTheDocument();

    await pick(tr.enums.scmType.GITHUB);
    await userEvent.click(screen.getByRole('switch', { name: ownLabel }));
    await pick(tr.enums.scmType.GIT);
    expect(screen.queryByRole('switch', { name: ownLabel })).not.toBeInTheDocument();
    await pick(tr.enums.scmType.GITHUB);
    expect(screen.getByRole('switch', { name: ownLabel })).not.toBeChecked();
  });

  it('shows the type read-only when editing', async () => {
    scmBackend();
    renderApp('/admin/scm-connections/4');

    const type = await screen.findByLabelText(tr.admin.scm.fields.type);
    expect(type).toHaveValue(tr.enums.scmType.BITBUCKET_DC);
    expect(type).toHaveAttribute('readonly');
    expect(screen.getByText(tr.admin.scm.typeFixed)).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: tr.admin.scm.fields.type })).not.toBeInTheDocument();
  });

  it('labels the GitHub fields', async () => {
    scmBackend();
    server.use(http.get(apiUrl('/api/v1/admin/scm-connections/4'), () =>
      HttpResponse.json({ ...stored, type: 'GITHUB', includeProjects: ['shop'] })));
    renderApp('/admin/scm-connections/4');

    expect(await screen.findByText(tr.admin.scm.fields.organizations)).toBeInTheDocument();
    expect(screen.queryByText(tr.admin.scm.fields.includeProjects)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(tr.admin.scm.fields.repositoryUrls)).not.toBeInTheDocument();
  });

  it('shows the Git URLs one per line without tag fields', async () => {
    scmBackend();
    server.use(http.get(apiUrl('/api/v1/admin/scm-connections/4'), () =>
      HttpResponse.json({ ...stored, type: 'GIT', includeProjects: [],
        repositoryUrls: ['https://git.corp/a.git', 'https://git.corp/b.git'] })));
    renderApp('/admin/scm-connections/4');

    expect(await screen.findByLabelText(tr.admin.scm.fields.repositoryUrls))
      .toHaveValue('https://git.corp/a.git\nhttps://git.corp/b.git');
    expect(screen.queryByText(tr.admin.scm.fields.includeProjects)).not.toBeInTheDocument();
    expect(screen.queryByText(tr.admin.scm.fields.organizations)).not.toBeInTheDocument();
    expect(screen.queryByText(tr.admin.scm.fields.excludeRepos)).not.toBeInTheDocument();
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

  it('treats a trailing slash on the base URL as no target change', async () => {
    scmBackend();
    renderApp('/admin/scm-connections/4');

    const baseUrl = await screen.findByLabelText(tr.admin.scm.fields.baseUrl);
    await userEvent.type(baseUrl, '/');

    expect(screen.queryByText(tr.admin.secret.reenter(tr.admin.scm.secretTarget))).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: tr.admin.common.save })).toBeEnabled();
  });

  it('sends no admin request when a USER follows a deep link to the list', async () => {
    signedIn();
    const adminCalls: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname.includes('/api/v1/admin')) {
        adminCalls.push(request.url);
      }
    });
    try {
      renderApp('/admin/scm-connections');

      expect(await screen.findByText(tr.admin.forbidden)).toBeInTheDocument();
      expect(adminCalls).toEqual([]);
    } finally {
      server.events.removeAllListeners();
    }
  });

  it('keeps no typed token in the mutation cache after a save', async () => {
    const puts = scmBackend();
    const router = renderApp('/admin/scm-connections/4');

    await userEvent.type(await screen.findByLabelText(tr.admin.scm.fields.secret), 'typed-token-xyz');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toHaveProperty('secret', 'typed-token-xyz');
    await waitFor(() => expect(JSON.stringify(
      router.queryClient.getMutationCache().getAll().map((mutation) => mutation.state.variables ?? null))).not.toContain('typed-token-xyz'));
  });

  it('removes the detail from the cache after a delete', async () => {
    scmBackend();
    server.use(http.delete(apiUrl('/api/v1/admin/scm-connections/4'), () => new HttpResponse(null, { status: 204 })));
    const router = renderApp('/admin/scm-connections/4');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.delete }));
    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.confirm }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/scm-connections'));
    expect(router.queryClient.getQueryData(['scm-connection', 4])).toBeUndefined();
  });

  it('scans one enabled connection and links to the running run on a conflict', async () => {
    const posts: unknown[] = [];
    scmBackend();
    server.use(http.post(apiUrl('/api/v1/index/runs'), async ({ request }) => {
      posts.push(await request.json());
      return HttpResponse.json({ status: 409, detail: 'Index run 7 is in progress', runId: 7 }, { status: 409 });
    }));
    renderApp('/admin/scm-connections/4');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.runs.scanConnection }));

    await waitFor(() => expect(posts).toEqual([{ scope: 'CONNECTION', id: 4, force: false }]));
    expect(await screen.findByText(/Index run 7 is in progress/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: tr.admin.runs.openRunning })).toHaveAttribute('href', '/runs/7');
  });

  it('does not offer a scan for a disabled connection', async () => {
    scmBackend();
    server.use(http.get(apiUrl('/api/v1/admin/scm-connections/4'), () => HttpResponse.json({ ...stored, enabled: false })));
    renderApp('/admin/scm-connections/4');

    expect(await screen.findByRole('button', { name: tr.admin.common.test })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: tr.admin.runs.scanConnection })).not.toBeInTheDocument();
  });
});
