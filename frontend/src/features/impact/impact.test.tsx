import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import cytoscape from 'cytoscape';
import { MAX_FIT_ZOOM } from '../../components/graphView';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn, testUiConfig } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

vi.mock('cytoscape', () => ({ default: vi.fn(() => ({ on: vi.fn(), destroy: vi.fn(), zoom: vi.fn(() => 6), center: vi.fn() })) }));

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
      symbol: { id: 7, key: 'k#format(int)', kind: 'METHOD', display: 'format(int) [seed]' },
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
    expect(await within(screen.getByRole('group', { name: tr.impact.seeds })).findByText('format(int) [seed]'))
      .toBeInTheDocument();
  });

  it('does not re-run the analysis on window focus', async () => {
    const requests: unknown[] = [];
    impactBackend({ requests });
    renderApp('/impact?symbol=7');
    await screen.findByText('/orders/checkout');

    window.dispatchEvent(new Event('focus'));
    document.dispatchEvent(new Event('visibilitychange'));
    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(requests).toHaveLength(1);
    expect(screen.getByText('/orders/checkout')).toBeInTheDocument();
  });

  it('draws the graph from the seeds and destroys it on leaving', async () => {
    impactBackend();
    const destroy = vi.fn();
    const zoom = vi.fn(() => 6);
    vi.mocked(cytoscape).mockClear();
    vi.mocked(cytoscape).mockReturnValue({ on: vi.fn(), destroy, zoom, center: vi.fn() } as never);
    const router = renderApp('/impact?symbol=7');
    await screen.findByText('/orders/checkout');
    await screen.findByTestId('impact-graph');

    await waitFor(() => expect(cytoscape).toHaveBeenCalledWith(expect.objectContaining({
      layout: expect.objectContaining({ roots: ['7'] }),
    })));
    // a small impact graph is not blown up to fill the canvas
    expect(zoom).toHaveBeenCalledWith(MAX_FIT_ZOOM);
    await router.navigate('/');
    await waitFor(() => expect(destroy).toHaveBeenCalled());
  });

  it('shows the backend message when the export fails', async () => {
    impactBackend();
    server.use(http.post(apiUrl('/api/v1/impact/export'), () =>
      HttpResponse.json({ status: 400, detail: 'export refused' }, { status: 400 })));
    renderApp('/impact?symbol=7');
    await screen.findByText('/orders/checkout');

    await userEvent.click(screen.getByRole('button', { name: tr.impact.exportCsv }));

    expect(await screen.findByText('export refused')).toBeInTheDocument();
  });

  it('a result over the node limit shows no graph and pages its locations', async () => {
    impactBackend({ body: { ...result(5, 3), truncated: true }, uiConfig: { graphMaxNodes: 4, pageDefaultSize: 2 } });
    renderApp('/impact?symbol=7');

    expect(await screen.findByText(tr.impact.truncated)).toBeInTheDocument();
    expect(screen.getByText(tr.impact.graphTooLarge(5, 4))).toBeInTheDocument();
    expect(screen.queryByTestId('impact-graph')).not.toBeInTheDocument();
    expect(screen.getByText('call0()')).toBeInTheDocument();
    expect(screen.queryByText('call2()')).not.toBeInTheDocument();
    // on a phone the locations table scrolls inside its own container instead of widening the page
    const locations = screen.getByText(tr.impact.locationColumns.snippet).closest('table')!;
    expect(locations.closest('.mantine-TableScrollContainer-scrollContainer')).not.toBeNull();
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
