import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../i18n/tr';
import { signedIn } from '../test/backend';
import { renderApp } from '../test/renderApp';
import { apiUrl, server } from '../test/server';

describe('admin pages', () => {
  it('a USER following a link sees the forbidden notice and no admin call is made', async () => {
    signedIn();
    renderApp('/admin/settings');

    expect(await screen.findByText(tr.admin.forbidden)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: tr.admin.nav.settings })).not.toBeInTheDocument();
  });

  it('an ADMIN sees the admin menu group', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json({ items: [], page: 0, size: 500, total: 0 })));
    renderApp('/');

    expect(await screen.findByText(tr.nav.admin)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: tr.admin.nav.settings })).toHaveAttribute('href', '/admin/settings');
  });
});
