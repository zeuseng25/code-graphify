import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

const repoRef = { id: 3, projectKey: 'SHOP', slug: 'api' };
const summary = { id: 3, projectKey: 'SHOP', slug: 'api', defaultBranch: 'main', active: true, moduleCount: 0 };

function runsBackend(starts: unknown[], connections: unknown = [
  { id: 4, name: 'corp', type: 'BITBUCKET_DC', enabled: true },
  { id: 5, name: 'old-scm', type: 'BITBUCKET_DC', enabled: false },
]) {
  signedIn({ user: { role: 'ADMIN' } });
  server.use(
    http.get(apiUrl('/api/v1/index/runs'), () => HttpResponse.json({ items: [], page: 0, size: 50, total: 0 })),
    http.get(apiUrl('/api/v1/admin/scm-connections'), () => HttpResponse.json(connections as object)),
    http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json({ items: [summary], page: 0, size: 500, total: 1 })),
    http.post(apiUrl('/api/v1/index/runs'), async ({ request }) => {
      starts.push(await request.json());
      return HttpResponse.json({ status: 409, detail: 'Index run 7 is in progress', runId: 7 }, { status: 409 });
    }),
  );
}

async function pickScope(label: string) {
  await userEvent.click(await screen.findByRole('combobox', { name: tr.admin.runs.scope }));
  await userEvent.click(await screen.findByRole('option', { name: label, hidden: true }));
}

describe('start run form', () => {
  it('starts a CONNECTION run with the chosen enabled connection and force', async () => {
    const starts: unknown[] = [];
    runsBackend(starts);
    renderApp('/runs');

    await pickScope(tr.enums.runScope.CONNECTION);
    await userEvent.click(await screen.findByRole('combobox', { name: tr.admin.runs.connection }));
    expect(screen.queryByRole('option', { name: 'old-scm', hidden: true })).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('option', { name: 'corp', hidden: true }));
    await userEvent.click(screen.getByRole('switch', { name: tr.admin.runs.force }));
    await userEvent.click(screen.getByRole('button', { name: tr.admin.runs.start }));

    await waitFor(() => expect(starts).toEqual([{ scope: 'CONNECTION', id: 4, force: true }]));
  });

  it('starts a REPOSITORY run and clears the previous conflict when the inputs change', async () => {
    const starts: unknown[] = [];
    runsBackend(starts);
    renderApp('/runs');

    await pickScope(tr.enums.runScope.REPOSITORY);
    await userEvent.click(await screen.findByRole('combobox', { name: tr.admin.runs.repository }));
    await userEvent.click(await screen.findByRole('option', { name: /SHOP\/api/, hidden: true }));
    await userEvent.click(screen.getByRole('button', { name: tr.admin.runs.start }));

    await waitFor(() => expect(starts).toEqual([{ scope: 'REPOSITORY', id: 3, force: false }]));
    expect(await screen.findByRole('link', { name: tr.admin.runs.openRunning })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('switch', { name: tr.admin.runs.force }));
    await waitFor(() => expect(screen.queryByRole('link', { name: tr.admin.runs.openRunning })).not.toBeInTheDocument());
  });

  it('shows the connection list failure with a retry', async () => {
    runsBackend([]);
    server.use(http.get(apiUrl('/api/v1/admin/scm-connections'), () => HttpResponse.json({ title: 'x' }, { status: 500 })));
    renderApp('/runs');

    await pickScope(tr.enums.runScope.CONNECTION);

    expect(await screen.findByRole('button', { name: tr.errors.retry })).toBeInTheDocument();
  });
});

describe('cancel run button', () => {
  const running = (cancelRequested = false, status = 'RUNNING') => ({
    run: { id: 9, trigger: 'MANUAL', scope: 'ALL', status, startedBy: 'ayse', startedAt: '2026-10-07T09:00:00Z', cancelRequested },
    repositoriesByStatus: {}, repositories: [], inProgress: [],
  });

  it('confirms, then posts the cancel', async () => {
    let cancels = 0;
    signedIn({ user: { role: 'ADMIN' }, uiConfig: { pollIntervalMillis: 60_000 } });
    server.use(
      http.get(apiUrl('/api/v1/index/runs/9'), () => HttpResponse.json(running())),
      http.post(apiUrl('/api/v1/index/runs/9/cancel'), () => { cancels++; return new HttpResponse(null, { status: 202 }); }),
    );
    renderApp('/runs/9');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.runs.cancel }));
    expect(cancels).toBe(0);
    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.confirm }));

    await waitFor(() => expect(cancels).toBe(1));
  });

  it('is hidden for a USER and for a run that is not RUNNING', async () => {
    signedIn({ uiConfig: { pollIntervalMillis: 60_000 } });
    server.use(http.get(apiUrl('/api/v1/index/runs/9'), () => HttpResponse.json(running())));
    renderApp('/runs/9');
    expect(await screen.findByLabelText(tr.runs.title)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: tr.admin.runs.cancel })).not.toBeInTheDocument();
  });

  it('is hidden for an admin when the run has finished', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(http.get(apiUrl('/api/v1/index/runs/9'), () => HttpResponse.json(running(false, 'SUCCESS'))));
    renderApp('/runs/9');
    expect(await within(await screen.findByLabelText(tr.runs.title)).findByText(tr.enums.runStatus.SUCCESS)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: tr.admin.runs.cancel })).not.toBeInTheDocument();
  });
});

describe('scan repository button', () => {
  function repoBackend(starts: unknown[]) {
    server.use(
      http.get(apiUrl('/api/v1/repositories/3'), () => HttpResponse.json({ repository: summary, modules: [] })),
      http.get(apiUrl('/api/v1/repositories/3/runs'), () => HttpResponse.json({ items: [], page: 0, size: 50, total: 0 })),
      http.post(apiUrl('/api/v1/index/runs'), async ({ request }) => {
        starts.push(await request.json());
        return HttpResponse.json({ status: 409, detail: 'Index run 7 is in progress', runId: 7 }, { status: 409 });
      }),
    );
  }

  it('posts a forced REPOSITORY scan and links to the running run on a conflict', async () => {
    const starts: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    repoBackend(starts);
    renderApp('/repositories/3');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.runs.scanRepository }));

    await waitFor(() => expect(starts).toEqual([{ scope: 'REPOSITORY', id: repoRef.id, force: true }]));
    expect(await screen.findByRole('link', { name: tr.admin.runs.openRunning })).toHaveAttribute('href', '/runs/7');
  });

  it('is hidden for a USER', async () => {
    signedIn();
    repoBackend([]);
    renderApp('/repositories/3');

    expect(await screen.findByRole('link', { name: tr.graph.show })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: tr.admin.runs.scanRepository })).not.toBeInTheDocument();
  });
});
