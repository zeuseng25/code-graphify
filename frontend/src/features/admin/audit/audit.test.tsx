import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { expect, it } from 'vitest';
import type { components } from '../../../api/schema';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';
import { ACTION_GROUP } from './AuditPage';

type AuditAction = components['schemas']['AuditAction'];

it('filters the audit log through the URL and the API', async () => {
  const seen: URL[] = [];
  signedIn({ user: { role: 'ADMIN' } });
  server.use(http.get(apiUrl('/api/v1/admin/audit'), ({ request }) => {
    seen.push(new URL(request.url));
    return HttpResponse.json({ items: [{ id: 1, actor: 'admin', action: 'SETTING_UPDATED', target: 'graph.max_nodes',
      details: '500 → 800', at: '2026-10-07T09:00:00Z' }], page: 0, size: 50, total: 1 });
  }));
  const router = renderApp('/admin/audit');

  // the action is shown by its Turkish name, the code stays as the tooltip
  const table = await screen.findByRole('table');
  const action = within(table).getByText(tr.enums.auditAction.SETTING_UPDATED);
  expect(action).toHaveAttribute('title', 'SETTING_UPDATED');
  expect(within(table).queryByText('SETTING_UPDATED')).not.toBeInTheDocument();
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

it('the action is picked by its Turkish name from a grouped list, not typed as a code', async () => {
  const seen: URL[] = [];
  signedIn({ user: { role: 'ADMIN' } });
  server.use(http.get(apiUrl('/api/v1/admin/audit'), ({ request }) => {
    seen.push(new URL(request.url));
    return HttpResponse.json({ items: [], page: 0, size: 50, total: 0 });
  }));
  const router = renderApp('/admin/audit');

  const picker = await screen.findByRole('combobox', { name: tr.admin.audit.action });
  await userEvent.click(picker);
  const list = document.getElementById(picker.getAttribute('aria-controls')!)!;
  expect(within(list).getByText(tr.admin.audit.actionGroups.session)).toBeInTheDocument();
  await userEvent.click(await within(list).findByRole('option', { name: tr.enums.auditAction.LOGIN_FAILED, hidden: true }));
  await userEvent.click(screen.getByRole('button', { name: tr.common.apply }));

  await waitFor(() => expect(router.state.location.search).toBe('?action=LOGIN_FAILED'));
  await waitFor(() => expect(seen.at(-1)?.searchParams.get('action')).toBe('LOGIN_FAILED'));
});

it('every action the API knows has a group and a Turkish name', () => {
  const actions = Object.keys(ACTION_GROUP) as AuditAction[];
  expect(actions).toHaveLength(21);
  for (const action of actions) {
    expect(tr.enums.auditAction[action], action).toBeTruthy();
    expect(tr.admin.audit.actionGroups[ACTION_GROUP[action]], action).toBeTruthy();
  }
});
