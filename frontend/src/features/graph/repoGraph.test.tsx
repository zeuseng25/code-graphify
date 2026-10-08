import { act, screen, waitFor, within } from '@testing-library/react';
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
    style: vi.fn(),
    destroy: vi.fn(),
    zoom: vi.fn(() => 1),
    center: vi.fn(),
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
    const elements = (vi.mocked(cytoscape).mock.calls.at(-1)![0] as unknown as { elements: { data: { id: string } }[] }).elements;
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
    const crumbs = screen.getByRole('navigation', { name: tr.graph.breadcrumb });
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
    const crumbs = screen.getByRole('navigation', { name: tr.graph.breadcrumb });
    expect(within(crumbs).queryByText(tr.graph.crumbs.classes)).not.toBeInTheDocument();
  });

  it('shows the backend message for an unknown focus', async () => {
    graphBackend({ body: { status: 404, detail: 'No package com.gone in repository 3' }, status: 404 });
    renderApp('/repositories/3/graph?level=CLASS&focus=com.gone');

    expect(await screen.findByText('No package com.gone in repository 3')).toBeInTheDocument();
    expect(screen.queryByTestId('repo-graph')).not.toBeInTheDocument();
  });

  it('the title links back to the repository and shows no placeholder while it loads', async () => {
    graphBackend();
    renderApp('/repositories/3/graph?level=CLASS');

    expect(screen.queryByText(/—\s*—/)).not.toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'SHOP/api' })).toHaveAttribute('href', '/repositories/3');
  });

  it('the shown level is highlighted when it differs from the requested one', async () => {
    graphBackend({ body: graphBody({ requestedLevel: 'CLASS', level: 'PACKAGE', focus: undefined, truncated: true,
      nodes: [{ id: 'package:com.shop.api', label: 'com.shop.api', type: 'PACKAGE', size: 2 }], edges: [] }) });
    renderApp('/repositories/3/graph?level=CLASS');

    await screen.findByTestId('repo-graph');
    expect(screen.getByRole('radio', { name: tr.enums.graphLevel.PACKAGE })).toBeChecked();
    expect(screen.getByRole('radio', { name: tr.enums.graphLevel.METHOD })).toBeDisabled();
  });

  it('a tapped edge caption is cleared when the graph changes', async () => {
    signedIn();
    server.use(
      http.get(apiUrl('/api/v1/repositories/3'), () => HttpResponse.json({ repository: summary, modules: [] })),
      http.get(apiUrl('/api/v1/repositories/3/graph/report'), () => HttpResponse.json({ repositoryId: 3, stale: false })),
      http.get(apiUrl('/api/v1/repositories/3/graph'), ({ request }) => {
        const level = new URL(request.url).searchParams.get('level');
        return HttpResponse.json(level === 'MODULE'
          ? graphBody({ level: 'MODULE', requestedLevel: 'MODULE', focus: undefined,
            nodes: [{ id: 'module:a', label: 'a', type: 'MODULE', size: 1 }], edges: [] })
          : graphBody());
      }),
    );
    renderApp('/repositories/3/graph?level=CLASS&focus=com.shop.api');
    await screen.findByTestId('repo-graph');
    await waitFor(() => expect(mocks.handlers.some((h) => h.event === 'tap' && h.selector === 'edge')).toBe(true));

    act(() => {
      mocks.handlers.filter((h) => h.event === 'tap' && h.selector === 'edge').at(-1)!
        .fn({ target: { id: () => 'class:com.shop.api.Checkout->class:com.shop.api.Cart' } });
    });
    expect(await screen.findByText(/Checkout → .*Cart/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('radio', { name: tr.enums.graphLevel.MODULE }));

    await waitFor(() => expect(screen.getByText(tr.graph.tapHint)).toBeInTheDocument());
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
