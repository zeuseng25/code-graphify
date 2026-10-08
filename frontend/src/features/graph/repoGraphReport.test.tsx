import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

vi.mock('cytoscape', () => ({ default: vi.fn(() => ({ on: vi.fn(), style: vi.fn(), destroy: vi.fn(), zoom: vi.fn(() => 1), center: vi.fn() })) }));

const report = {
  repositoryId: 3, indexedCommit: 'bbb222', analyzedCommit: 'aaa111', analyzedAt: '2026-10-07T09:00:00Z', stale: true,
  moduleCount: 2, packageCount: 4, classCount: 7, dependencyCount: 9, communityCount: 2, entryPointCount: 1,
  criticalClasses: [{ symbolId: 41, fqn: 'com.g.a.AlphaHelper', inDegree: 2, outDegree: 0, dependents: 5, entryPoint: true }],
  communities: [{ id: 1, label: 'com.g.a', size: 4 }],
  cycles: [{ id: 1, packages: ['com.g.a', 'com.g.b'] }],
  entryPointClasses: ['com.g.web.Api'],
};

function backend(options: { exports?: URL[]; exportStatus?: number; report?: unknown } = {}) {
  signedIn();
  server.use(
    http.get(apiUrl('/api/v1/repositories/3'), () => HttpResponse.json({ repository: { id: 3, projectKey: 'SHOP', slug: 'api' }, modules: [] })),
    http.get(apiUrl('/api/v1/repositories/3/graph'), () => HttpResponse.json({
      repositoryId: 3, requestedLevel: 'CLASS', level: 'CLASS', focus: 'com.g', truncated: false,
      nodes: [{ id: 'class:com.g.a.Alpha', label: 'com.g.a.Alpha', type: 'CLASS', size: 1 }], edges: [],
    })),
    http.get(apiUrl('/api/v1/repositories/3/graph/report'), () => HttpResponse.json(options.report ?? report)),
    http.get(apiUrl('/api/v1/repositories/3/graph/export'), ({ request }) => {
      options.exports?.push(new URL(request.url));
      if (options.exportStatus) {
        return HttpResponse.json({ status: options.exportStatus, detail: 'format must be graphml or json' }, { status: options.exportStatus });
      }
      return new HttpResponse('<graphml/>', { headers: {
        'Content-Type': 'application/xml', 'Content-Disposition': 'attachment; filename="repository-3-class.graphml"',
      } });
    }),
  );
}

afterEach(() => vi.restoreAllMocks());

describe('repository graph report', () => {
  it('the report shows counts, links into the graph and warns when stale', async () => {
    backend();
    renderApp('/repositories/3/graph?level=CLASS&focus=com.g');

    const panel = await screen.findByRole('region', { name: tr.graph.report.title });
    expect(await within(panel).findByText(tr.graph.report.stale('bbb222', 'aaa111'))).toBeInTheDocument();
    // a critical class shows its simple name with its package under it, so the narrow panel never overflows; its
    // accessible name stays the full name, so two classes with the same simple name are told apart
    const critical = within(panel).getByRole('link', { name: 'com.g.a.AlphaHelper' });
    expect(critical).toHaveTextContent(/^AlphaHelper$/);
    expect(critical).toHaveAttribute('href', '/repositories/3/graph?level=METHOD&focus=com.g.a.AlphaHelper');
    expect(critical).toHaveAttribute('title', 'com.g.a.AlphaHelper');
    const nameCell = critical.closest('td')!;
    expect(within(nameCell).getByText('com.g.a')).toBeInTheDocument();
    expect(nameCell.style.overflowWrap).toBe('anywhere');
    // the entry point badge sits with the name (no column of its own) and is never clipped to "EN…"
    const entry = within(nameCell).getByText(tr.graph.report.entryPoint);
    expect(entry.style.overflow).toBe('visible');
    expect(critical.closest('tr')!.querySelectorAll('td')).toHaveLength(3);
    // long package and class names in the lists wrap inside the panel instead of widening the page
    expect(panel.style.overflowWrap).toBe('anywhere');
    expect(within(panel).getByRole('link', { name: tr.graph.report.openSymbol })).toHaveAttribute('href', '/symbols/41');
    expect(within(panel).getByRole('link', { name: 'com.g.b' }))
      .toHaveAttribute('href', '/repositories/3/graph?level=CLASS&focus=com.g.b');
    expect(within(panel).getByRole('link', { name: 'com.g.web.Api' }))
      .toHaveAttribute('href', '/repositories/3/graph?level=METHOD&focus=com.g.web.Api');
    expect(within(panel).getByText(tr.graph.report.communitySize('4'))).toBeInTheDocument();
  });

  it('report links keep the current includeExternal', async () => {
    backend();
    renderApp('/repositories/3/graph?level=CLASS&focus=com.g&includeExternal=true');

    const panel = await screen.findByRole('region', { name: tr.graph.report.title });
    expect(await within(panel).findByRole('link', { name: 'com.g.b' }))
      .toHaveAttribute('href', '/repositories/3/graph?level=CLASS&focus=com.g.b&includeExternal=true');
  });

  it('says so when there are no critical classes, communities or entry points', async () => {
    backend({ report: { ...report, criticalClasses: [], communities: [], entryPointClasses: [] } });
    renderApp('/repositories/3/graph?level=CLASS');

    const panel = await screen.findByRole('region', { name: tr.graph.report.title });
    expect(await within(panel).findByText(tr.graph.report.noCritical)).toBeInTheDocument();
    expect(within(panel).getByText(tr.graph.report.noCommunities)).toBeInTheDocument();
    expect(within(panel).getByText(tr.graph.report.noEntryPoints)).toBeInTheDocument();
  });

  it('exports the graph the URL describes', async () => {
    const exports: URL[] = [];
    backend({ exports });
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    renderApp('/repositories/3/graph?level=CLASS&focus=com.g&includeExternal=true');

    await userEvent.click(await screen.findByRole('button', { name: tr.graph.export.graphml }));

    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    const sent = exports.at(-1)!.searchParams;
    expect([sent.get('format'), sent.get('level'), sent.get('focus'), sent.get('includeExternal')])
      .toEqual(['graphml', 'CLASS', 'com.g', 'true']);
  });

  it('a rejected export shows the backend message', async () => {
    backend({ exportStatus: 400 });
    renderApp('/repositories/3/graph?level=CLASS');

    await userEvent.click(await screen.findByRole('button', { name: tr.graph.export.json }));

    expect(await screen.findByText('format must be graphml or json')).toBeInTheDocument();
  });
});
