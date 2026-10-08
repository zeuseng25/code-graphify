import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

const repo = { id: 3, projectKey: 'SHOP', slug: 'api' };

function symbolBackend(usageRequests: URL[] = []) {
  signedIn();
  server.use(
    http.get(apiUrl('/api/v1/symbols/7'), () => HttpResponse.json({
      symbol: { id: 7, key: 'com.shop.lib.PriceFormatter#format(int)', kind: 'METHOD', display: 'format(int)' },
      origin: 'SOURCE', nameOnly: false,
      parent: { id: 6, key: 'com.shop.lib.PriceFormatter', kind: 'CLASS', display: 'PriceFormatter' },
      declarations: [{ repository: { id: 1, projectKey: 'SHOP', slug: 'lib' }, modulePath: 'shop-lib',
        filePath: 'src/main/java/com/shop/lib/PriceFormatter.java', line: 12 }],
      members: [], overrides: [], overriddenBy: [], supertypes: [], subtypes: [],
    })),
    http.get(apiUrl('/api/v1/symbols/7/usages/summary'), () => HttpResponse.json({
      usages: 12, repositories: 1,
      byRepository: [{ repository: repo, usages: 12, modules: [{ modulePath: 'shop-api', usages: 12,
        classes: [{ classFqn: 'com.shop.api.CheckoutService', usages: 12 }] }] }],
    })),
    http.get(apiUrl('/api/v1/symbols/7/usages'), ({ request }) => {
      usageRequests.push(new URL(request.url));
      return HttpResponse.json({
        items: [{ id: 100, from: { id: 9, key: 'com.shop.api.CheckoutService#total()', kind: 'METHOD', display: 'total()' },
          kind: 'CALL', confidence: 'EXACT', repository: repo, modulePath: 'shop-api',
          filePath: 'src/main/java/com/shop/api/CheckoutService.java', line: 31, column: 9,
          snippet: 'formatter.format(sum)' }],
        page: 0, size: 50, total: 1,
      });
    }),
  );
}

describe('symbol detail', () => {
  it('shows where the symbol is declared, how much it is used and each usage', async () => {
    symbolBackend();
    renderApp('/symbols/7');

    expect(await screen.findByRole('heading', { name: 'format(int)' })).toBeInTheDocument();
    expect(screen.getByText('src/main/java/com/shop/lib/PriceFormatter.java:12')).toBeInTheDocument();
    const summary = await screen.findByRole('region', { name: tr.symbol.summary });
    expect(await within(summary).findByText('SHOP/api')).toBeInTheDocument();
    expect(await screen.findByText('formatter.format(sum)')).toBeInTheDocument();
    const row = screen.getByText('formatter.format(sum)').closest('tr')!;
    expect(within(row).getByText(tr.enums.confidence.EXACT)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'total()' })).toHaveAttribute('href', '/symbols/9');
  });

  it('keeps the kind and confidence badges whole and wraps the long cells', async () => {
    symbolBackend();
    renderApp('/symbols/7');

    const snippet = await screen.findByText('formatter.format(sum)');
    const row = snippet.closest('tr')!;
    const kind = within(row).getByText(tr.enums.usageKind.CALL);
    const confidence = within(row).getByText(tr.enums.confidence.EXACT);
    expect(kind.closest('td')?.style.whiteSpace).toBe('nowrap');
    expect(confidence.closest('td')?.style.whiteSpace).toBe('nowrap');
    expect(kind.style.overflow).toBe('visible');
    expect(confidence.style.overflow).toBe('visible');
    expect(snippet.closest('td')?.style.overflowWrap).toBe('anywhere');
    // on a phone the usages table scrolls inside its own container instead of widening the page
    expect(snippet.closest('.mantine-TableScrollContainer-scrollContainer')).not.toBeNull();
    // the declaration's long file path wraps
    const declaration = screen.getByText('src/main/java/com/shop/lib/PriceFormatter.java:12');
    expect(declaration.closest('td')?.style.overflowWrap).toBe('anywhere');
  });

  it('filters usages by confidence through the URL and the API', async () => {
    const requests: URL[] = [];
    symbolBackend(requests);
    const router = renderApp('/symbols/7?confidence=EXACT&usageKind=CALL');

    await screen.findByText('formatter.format(sum)');
    expect(router.state.location.search).toContain('confidence=EXACT');
    await waitFor(() => expect(requests.at(-1)?.searchParams.getAll('confidence')).toEqual(['EXACT']));
    expect(requests.at(-1)?.searchParams.getAll('kind')).toEqual(['CALL']);
  });

  it('starts an impact analysis for this symbol', async () => {
    symbolBackend();
    const router = renderApp('/symbols/7');

    await screen.findByRole('heading', { name: 'format(int)' });
    const link = within(screen.getByRole('main')).getByRole('link', { name: tr.symbol.analyzeImpact });
    expect(link).toHaveAttribute('href', '/impact?symbol=7');
    await userEvent.click(link);

    await waitFor(() => expect(router.state.location.pathname).toBe('/impact'));
    expect(router.state.location.search).toBe('?symbol=7');
  });

  it('keeps the shown symbol when a later refetch fails', async () => {
    symbolBackend();
    const queryClient = renderApp('/symbols/7').queryClient;
    expect(await screen.findByText('format(int)', { selector: 'h2' })).toBeInTheDocument();
    server.use(http.get(apiUrl('/api/v1/symbols/7'), () => HttpResponse.json({ title: 'x' }, { status: 500 })));

    await queryClient.invalidateQueries({ queryKey: ['symbol', 7] });
    await waitFor(() => expect(queryClient.getQueryState(['symbol', 7])?.status).toBe('error'));
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(screen.getByText('format(int)', { selector: 'h2' })).toBeInTheDocument();
    expect(screen.queryByText(tr.errors.retry)).not.toBeInTheDocument();
  });

  it('shows the filtered usage total next to the title', async () => {
    symbolBackend();
    renderApp('/symbols/7');
    expect(await screen.findByRole('heading', { name: new RegExp(`${tr.symbol.usages} ${tr.symbol.usagesTotal('1').replace(/[()]/g, '\\$&')}`) })).toBeInTheDocument();
  });

  it('an unknown symbol shows the backend message', async () => {
    signedIn();
    server.use(
      http.get(apiUrl('/api/v1/symbols/404'), () =>
        HttpResponse.json({ status: 404, detail: 'No symbol with id 404' }, { status: 404 })),
      http.get(apiUrl('/api/v1/symbols/404/usages/summary'), () => HttpResponse.json({ status: 404 }, { status: 404 })),
      http.get(apiUrl('/api/v1/symbols/404/usages'), () => HttpResponse.json({ status: 404 }, { status: 404 })),
    );
    renderApp('/symbols/404');

    expect(await screen.findByText('No symbol with id 404')).toBeInTheDocument();
  });

  it('a non-numeric id is not found', async () => {
    signedIn();
    renderApp('/symbols/abc');

    expect(await screen.findByText(tr.errors.notFound)).toBeInTheDocument();
  });

  it('the summary lists classes per module', async () => {
    symbolBackend();
    renderApp('/symbols/7');

    const summary = await screen.findByRole('region', { name: tr.symbol.summary });
    await userEvent.click(await within(summary).findByText('SHOP/api'));
    expect(await within(summary).findByText('com.shop.api.CheckoutService')).toBeInTheDocument();
  });
});

describe('usage filters', () => {
  it('choosing a confidence updates the URL, drops the page and asks the API', async () => {
    const requests: URL[] = [];
    symbolBackend(requests);
    const router = renderApp('/symbols/7?page=2');
    await screen.findByText('formatter.format(sum)');

    await userEvent.click(screen.getAllByLabelText(tr.symbol.filters.confidence).find((el) => el.tagName === 'INPUT')!);
    await userEvent.click(await screen.findByRole('option', { name: tr.enums.confidence.EXACT, hidden: true }));

    await waitFor(() => expect(router.state.location.search).toBe('?confidence=EXACT'));
    await waitFor(() => expect(requests.at(-1)?.searchParams.getAll('confidence')).toEqual(['EXACT']));
    expect(requests.at(-1)?.searchParams.get('page')).toBe('0');
  });
});
