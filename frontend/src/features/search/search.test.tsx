import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { signedIn } from '../../test/backend';
import { renderApp } from '../../test/renderApp';
import { apiUrl, server } from '../../test/server';

const repositories = { items: [{ id: 3, projectKey: 'SHOP', slug: 'api' }], page: 0, size: 500, total: 1 };

function searchBackend(onSearch: (url: URL) => void = () => {}) {
  signedIn();
  server.use(
    http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json(repositories)),
    http.get(apiUrl('/api/v1/symbols/search'), ({ request }) => {
      const url = new URL(request.url);
      onSearch(url);
      if (url.searchParams.get('q') === 'nothing') {
        return HttpResponse.json({ items: [], page: 0, size: 50, total: 0 });
      }
      return HttpResponse.json({
        items: [
          { id: 7, key: 'com.shop.lib.PriceFormatter#format(int)', kind: 'METHOD', display: 'format(int)',
            origin: 'SOURCE', nameOnly: false, usageCount: 12, repositoryCount: 3 },
          { id: 8, key: 'com.shop.lib.PriceFormatter#format(java.lang.String)', kind: 'METHOD',
            display: 'format(String)', origin: 'SOURCE', nameOnly: false, usageCount: 1, repositoryCount: 1 },
        ],
        page: 0, size: 50, total: 2,
      });
    }),
  );
}

describe('symbol search', () => {
  it('searches on submit and keeps the query in the URL', async () => {
    const seen: URL[] = [];
    searchBackend((url) => seen.push(url));
    const router = renderApp('/');

    await userEvent.type(await screen.findByLabelText(tr.search.query), 'PriceFormatter#format');
    expect(seen).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: tr.common.search }));

    expect(await screen.findByText('format(int)')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?q=PriceFormatter%23format');
    expect(seen.at(-1)?.searchParams.get('q')).toBe('PriceFormatter#format');
    expect(seen.at(-1)?.searchParams.has('size')).toBe(false);
  });

  it('lists overloads separately and opens the chosen one', async () => {
    searchBackend();
    const router = renderApp('/?q=format');

    const link = await screen.findByRole('link', { name: 'format(String)' });
    expect(link).toHaveAttribute('href', '/symbols/8');
    const usagesCell = (name: string) => within(screen.getByText(name).closest('tr')!).getAllByRole('cell')[3];
    expect(usagesCell('format(String)')).toHaveTextContent(/^1$/);
    expect(usagesCell('format(int)')).toHaveTextContent(/^12$/);
    await userEvent.click(link);

    await waitFor(() => expect(router.state.location.pathname).toBe('/symbols/8'));
  });

  it('says so when nothing matches', async () => {
    searchBackend();
    renderApp('/?q=nothing');

    expect(await screen.findByText(tr.common.empty)).toBeInTheDocument();
  });

  it('shows the backend message for a rejected query', async () => {
    signedIn();
    server.use(
      http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json(repositories)),
      http.get(apiUrl('/api/v1/symbols/search'), () =>
        HttpResponse.json({ status: 400, detail: 'q is too short' }, { status: 400 })),
    );
    renderApp('/?q=a');

    expect(await screen.findByText('q is too short')).toBeInTheDocument();
  });
});
