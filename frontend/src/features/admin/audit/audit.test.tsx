import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

it('filters the audit log through the URL and the API', async () => {
  const seen: URL[] = [];
  signedIn({ user: { role: 'ADMIN' } });
  server.use(http.get(apiUrl('/api/v1/admin/audit'), ({ request }) => {
    seen.push(new URL(request.url));
    return HttpResponse.json({ items: [{ id: 1, actor: 'admin', action: 'SETTING_CHANGED', target: 'graph.max_nodes',
      details: '500 → 800', at: '2026-10-07T09:00:00Z' }], page: 0, size: 50, total: 1 });
  }));
  const router = renderApp('/admin/audit');

  expect(await screen.findByText('SETTING_CHANGED')).toBeInTheDocument();
  await userEvent.type(screen.getByLabelText(tr.admin.audit.actor), 'admin');
  await userEvent.click(screen.getByRole('button', { name: tr.common.apply }));

  await waitFor(() => expect(router.state.location.search).toBe('?actor=admin'));
  await waitFor(() => expect(seen.at(-1)?.searchParams.get('actor')).toBe('admin'));
});

it('sends the from minute and the last millisecond of the to minute as instants', async () => {
  const seen: URL[] = [];
  signedIn({ user: { role: 'ADMIN' } });
  server.use(http.get(apiUrl('/api/v1/admin/audit'), ({ request }) => {
    seen.push(new URL(request.url));
    return HttpResponse.json({ items: [], page: 0, size: 50, total: 0 });
  }));
  renderApp('/admin/audit');

  const from = await screen.findByLabelText(tr.admin.audit.from);
  const to = screen.getByLabelText(tr.admin.audit.to);
  fireEvent.change(from, { target: { value: '2026-10-07T09:15' } });
  fireEvent.change(to, { target: { value: '2026-10-07T10:30' } });
  await userEvent.click(screen.getByRole('button', { name: tr.common.apply }));

  const expectedFrom = new Date('2026-10-07T09:15').toISOString();
  const expectedTo = new Date(new Date('2026-10-07T10:30').getTime() + 59_999).toISOString();
  await waitFor(() => expect(seen.at(-1)?.searchParams.get('from')).toBe(expectedFrom));
  expect(seen.at(-1)?.searchParams.get('to')).toBe(expectedTo);
});
