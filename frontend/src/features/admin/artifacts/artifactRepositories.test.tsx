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

  it('asks for the password again when the URL changes', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(http.get(apiUrl('/api/v1/admin/artifact-repositories/2'), () => HttpResponse.json(nexus)));
    renderApp('/admin/artifact-repositories/2');

    const url = await screen.findByLabelText(tr.admin.artifacts.fields.url);
    await userEvent.type(url, 'x');

    expect(await screen.findByText(tr.admin.secret.reenter(tr.admin.artifacts.secretTarget))).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: tr.admin.common.save })).toBeDisabled());
    expect(screen.getByRole('button', { name: tr.admin.common.test })).toBeDisabled();
  });

  it('removes the detail from the cache after delete', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/artifact-repositories'), () => HttpResponse.json([])),
      http.get(apiUrl('/api/v1/admin/artifact-repositories/2'), () => HttpResponse.json(nexus)),
      http.delete(apiUrl('/api/v1/admin/artifact-repositories/2'), () => new HttpResponse(null, { status: 204 })),
    );
    const router = renderApp('/admin/artifact-repositories/2');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.delete }));
    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.confirm }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/artifact-repositories'));
    expect(router.queryClient.getQueryData(['artifact-repository', 2])).toBeUndefined();
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
      renderApp('/admin/artifact-repositories');
      expect(await screen.findByText(tr.admin.forbidden)).toBeInTheDocument();
    } finally {
      server.events.removeAllListeners();
    }
    expect(adminCalls).toEqual([]);
  });

  it('requires a non-negative whole sort order', async () => {
    const posts: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(http.post(apiUrl('/api/v1/admin/artifact-repositories'), async ({ request }) => {
      posts.push(await request.json());
      return HttpResponse.json({ ...nexus, id: 6 }, { status: 201 });
    }));
    renderApp('/admin/artifact-repositories/new');

    await userEvent.type(await screen.findByLabelText(tr.admin.artifacts.fields.name), 'mirror');
    await userEvent.type(screen.getByLabelText(tr.admin.artifacts.fields.url), 'https://mirror.corp/maven/');
    await userEvent.clear(screen.getByLabelText(tr.admin.artifacts.fields.sortOrder));
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));

    expect(await screen.findByText(tr.errors.required)).toBeInTheDocument();
    expect(posts).toEqual([]);
  });
});
