import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

    const header = await screen.findByLabelText(tr.runs.title);
    expect(await screen.findByText('SHOP/lib')).toBeInTheDocument();
    expect(within(header).getByText(tr.enums.runStatus.RUNNING)).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByLabelText(tr.runs.title)).getByText(tr.enums.runStatus.SUCCESS)).toBeInTheDocument(),
      { timeout: 2000 });
    const settled = calls;
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(calls).toBe(settled);
  });

  it('a failed poll keeps the shown run', async () => {
    let calls = 0;
    signedIn({ uiConfig: { pollIntervalMillis: 20 } });
    server.use(http.get(apiUrl('/api/v1/index/runs/9'), () => {
      calls++;
      return calls === 1 ? HttpResponse.json(run('RUNNING')) : HttpResponse.json({ title: 'x' }, { status: 500 });
    }));
    renderApp('/runs/9');

    expect(await screen.findByText('SHOP/lib')).toBeInTheDocument();
    await waitFor(() => expect(calls).toBeGreaterThan(2));
    expect(screen.getByText('SHOP/lib')).toBeInTheDocument();
    expect(screen.queryByText(tr.errors.retry)).not.toBeInTheDocument();
  });

  it('stops polling when the page unmounts', async () => {
    let calls = 0;
    signedIn({ uiConfig: { pollIntervalMillis: 20 } });
    server.use(http.get(apiUrl('/api/v1/index/runs/9'), () => {
      calls++;
      return HttpResponse.json(run('RUNNING'));
    }));
    server.use(http.get(apiUrl('/api/v1/index/runs'), () => HttpResponse.json({ items: [], page: 0, size: 50, total: 0 })));
    const router = renderApp('/runs/9');
    expect(await screen.findByText('SHOP/lib')).toBeInTheDocument();
    await router.navigate('/runs');
    await new Promise((resolve) => setTimeout(resolve, 60));
    const settled = calls;
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(calls).toBe(settled);
  });

  it('shows each repository artifact install with its output', async () => {
    signedIn();
    server.use(http.get(apiUrl('/api/v1/index/runs/7'), () => HttpResponse.json({
      run: { id: 7, trigger: 'MANUAL', scope: 'ALL', status: 'SUCCESS', startedBy: 'admin', startedAt: '2026-10-08T10:00:00Z', cancelRequested: false },
      repositoriesByStatus: { SUCCESS: 2 },
      inProgress: [],
      repositories: [
        { runId: 7, repository: { id: 1, projectKey: 'ACME', slug: 'acme-lib' }, status: 'SUCCESS', artifactInstall: 'INSTALLED', symbolCount: 1, usageCount: 1, warningCount: 0, durationMs: 1000 },
        { runId: 7, repository: { id: 2, projectKey: 'ACME', slug: 'acme-bad' }, status: 'SUCCESS', artifactInstall: 'FAILED', artifactInstallError: 'Maven exited with 1:\n[ERROR] COMPILATION ERROR', symbolCount: 1, usageCount: 1, warningCount: 0, durationMs: 1000,
          error: 'GitException: clone https://***@scm/acme/acme-bad.git failed' },
      ],
    })));
    renderApp('/runs/7');

    expect(await screen.findByText(tr.enums.artifactInstall.INSTALLED)).toBeInTheDocument();
    expect(screen.getByText(tr.enums.artifactInstall.FAILED)).toBeInTheDocument();
    await userEvent.click(screen.getByText(tr.runs.installOutput));
    expect(screen.getByText(/COMPILATION ERROR/)).toBeVisible();
    // a long error wraps inside its cell, so the table needs no sideways scroll on a desktop
    expect(screen.getByText(/GitException: clone/).closest('td')?.style.overflowWrap).toBe('anywhere');
  });
});
