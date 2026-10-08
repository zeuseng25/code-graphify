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
