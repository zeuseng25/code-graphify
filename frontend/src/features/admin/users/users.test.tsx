import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';
import { CELL_SELECT_MIN_WIDTH } from '../../../components/wrapStyles';

const admin = { id: 1, username: 'admin', source: 'LOCAL', displayName: 'Yönetici', role: 'ADMIN', active: true };
const ayse = { id: 2, username: 'ayse', source: 'LDAP', displayName: 'Ayşe', role: 'USER', active: true };

function usersBackend() {
  const registrations: unknown[] = [];
  signedIn({ user: { role: 'ADMIN', username: 'admin' } });
  server.use(
    http.get(apiUrl('/api/v1/admin/users'), () => HttpResponse.json({ items: [admin, ayse], page: 0, size: 50, total: 2 })),
    http.put(apiUrl('/api/v1/admin/users/1/active'), () =>
      HttpResponse.json({ status: 409, detail: 'admin is the last active admin' }, { status: 409 })),
    http.get(apiUrl('/api/v1/admin/ldap/users'), () => HttpResponse.json([
      { username: 'ayse', displayName: 'Ayşe', appUserId: 2 },
      { username: 'mehmet', displayName: 'Mehmet', email: 'mehmet@corp' },
    ])),
    http.post(apiUrl('/api/v1/admin/users'), async ({ request }) => {
      registrations.push(await request.json());
      return HttpResponse.json({ id: 3, username: 'mehmet', source: 'LDAP', role: 'USER', active: true }, { status: 201 });
    }),
  );
  return registrations;
}

/** Opens the row's role select and picks an option from its own list. */
async function pickRole(row: HTMLElement, label: string) {
  const select = within(row).getByRole('combobox', { name: tr.admin.users.columns.role });
  await userEvent.click(select);
  const list = document.getElementById(select.getAttribute('aria-controls')!)!;
  await userEvent.click(await within(list).findByRole('option', { name: label, hidden: true }));
}

describe('users', () => {
  it('shows the last-admin rule', async () => {
    usersBackend();
    renderApp('/admin/users');

    const row = (await screen.findByText('admin')).closest('tr')!;
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.users.deactivate }));
    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.confirm }));

    expect(await screen.findByText('admin is the last active admin')).toBeInTheDocument();
    expect(within(row).getByText(tr.admin.common.enabled)).toBeInTheDocument();
  });

  it('a role picker keeps a readable width in a narrow table', async () => {
    usersBackend();
    renderApp('/admin/users');

    const row = (await screen.findByText('admin')).closest('tr')!;
    const picker = within(row).getByRole('combobox', { name: tr.admin.users.columns.role }).closest('.mantine-Select-root') as HTMLElement;
    // Mantine writes the px value as rem (16px = 1rem)
    expect(picker.style.minWidth).toContain(`${CELL_SELECT_MIN_WIDTH / 16}rem`);
  });

  it('adds a user found in the directory', async () => {
    const registrations = usersBackend();
    renderApp('/admin/users');

    await userEvent.type(await screen.findByLabelText(tr.admin.users.directoryQuery), 'meh');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.users.directorySearch }));

    const row = (await screen.findByText('mehmet')).closest('tr')!;
    expect(within((await screen.findAllByText('ayse')).at(-1)!.closest('tr')!).getByText(tr.admin.users.registered))
      .toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.users.add }));

    await waitFor(() => expect(registrations).toEqual([{ username: 'mehmet', role: 'USER' }]));
  });
});

describe('users, fix round 1', () => {
  it('a refused role change refetches the list and shows the server role', async () => {
    let gets = 0;
    usersBackend();
    server.use(
      http.get(apiUrl('/api/v1/admin/users'), () => {
        gets++;
        return HttpResponse.json({ items: [admin, ayse], page: 0, size: 50, total: 2 });
      }),
      http.put(apiUrl('/api/v1/admin/users/2/role'), () =>
        HttpResponse.json({ status: 409, detail: 'refused' }, { status: 409 })),
    );
    renderApp('/admin/users');

    const row = (await screen.findByText('ayse')).closest('tr')!;
    const before = gets;
    await pickRole(row, tr.header.roles.ADMIN);

    expect(await screen.findByText('refused')).toBeInTheDocument();
    await waitFor(() => expect(gets).toBeGreaterThan(before));
    await waitFor(() =>
      expect(within(row).getByRole('combobox', { name: tr.admin.users.columns.role })).toHaveValue(tr.header.roles.USER));
  });

  it('an admin demoting themselves loses the admin pages', async () => {
    let role = 'ADMIN';
    usersBackend();
    server.use(
      http.get(apiUrl('/api/v1/auth/me'), () =>
        HttpResponse.json({ username: 'admin', displayName: 'Yönetici', role, source: 'LOCAL', mustChangePassword: false })),
      http.get(apiUrl('/api/v1/admin/users'), () =>
        role === 'ADMIN'
          ? HttpResponse.json({ items: [admin, ayse], page: 0, size: 50, total: 2 })
          : HttpResponse.json({ status: 403, detail: 'forbidden' }, { status: 403 })),
      http.put(apiUrl('/api/v1/admin/users/1/role'), () => {
        role = 'USER';
        return HttpResponse.json({ ...admin, role: 'USER' });
      }),
    );
    renderApp('/admin/users');

    const row = (await screen.findByText('admin')).closest('tr')!;
    await pickRole(row, tr.header.roles.USER);
    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.confirm }));

    expect(await screen.findByText(tr.admin.forbidden)).toBeInTheDocument();
    expect(screen.queryByText(tr.admin.nav.users)).not.toBeInTheDocument();
  });

  it('saving the LDAP config invalidates cached directory searches', async () => {
    usersBackend();
    server.use(
      http.put(apiUrl('/api/v1/admin/ldap'), () => HttpResponse.json({ enabled: true, url: 'ldaps://x', bindPasswordSet: false })),
      http.get(apiUrl('/api/v1/admin/ldap'), () => HttpResponse.json({ enabled: true, url: 'ldaps://x', bindPasswordSet: false })),
    );
    const router = renderApp('/admin/users');

    await userEvent.type(await screen.findByLabelText(tr.admin.users.directoryQuery), 'meh');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.users.directorySearch }));
    await screen.findByText('mehmet');
    expect(router.queryClient.getQueryState(['directory', 'meh'])?.isInvalidated).toBe(false);

    await router.navigate('/admin/ldap');
    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.save }));

    await waitFor(() => expect(router.queryClient.getQueryState(['directory', 'meh'])?.isInvalidated).toBe(true));
  });
});

describe('users, own role', () => {
  it('asks before changing your own role and does nothing on cancel', async () => {
    const puts: unknown[] = [];
    usersBackend();
    server.use(http.put(apiUrl('/api/v1/admin/users/1/role'), async ({ request }) => {
      puts.push(await request.json());
      return HttpResponse.json(admin);
    }));
    renderApp('/admin/users');

    const row = (await screen.findByText('admin')).closest('tr')!;
    await pickRole(row, tr.header.roles.USER);
    expect(await screen.findByText(tr.admin.users.selfRoleConfirm(tr.header.roles.USER))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.cancel }));

    expect(puts).toEqual([]);
  });
});
