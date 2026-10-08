import { screen, waitFor, within } from '@testing-library/react';
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

    expect(await screen.findByRole('link', { name: 'SHOP/api' })).toHaveAttribute('href', '/repositories/3');
    expect(within(screen.getByRole('table')).getByText(tr.enums.repoStatus.SUCCESS_PARTIAL)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('combobox', { name: tr.repositories.status }));
    await userEvent.click(await screen.findByRole('option', { name: tr.enums.repoStatus.FAILED, hidden: true }));

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
    expect(screen.getByText('clone of https://***@scm/x failed').closest('td')?.style.overflowWrap).toBe('anywhere');
    expect(screen.getByRole('link', { name: '#41' })).toHaveAttribute('href', '/runs/41');
  });

  it('renders the not-found view for an invalid id', async () => {
    signedIn();
    renderApp('/repositories/abc');
    expect(await screen.findByText(tr.errors.notFound)).toBeInTheDocument();
  });

  it('marks missing module coordinates', async () => {
    signedIn();
    server.use(
      http.get(apiUrl('/api/v1/repositories/3'), () => HttpResponse.json({ repository: summary, modules: [
        { id: 30, path: 'x', artifactId: 'only-artifact' },
      ] })),
      http.get(apiUrl('/api/v1/repositories/3/runs'), () => HttpResponse.json({ items: [], page: 0, size: 50, total: 0 })),
    );
    renderApp('/repositories/3');
    expect(await screen.findByText(`${tr.common.none}:only-artifact:${tr.common.none}`)).toBeInTheDocument();
  });
});
