import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

const config = { enabled: true, url: 'ldaps://ldap.corp', baseDn: 'dc=corp', userSearchBase: 'ou=people',
  userSearchFilter: '(uid={0})', userQueryFilter: '(|(uid=*{0}*)(cn=*{0}*))', usernameAttr: 'uid',
  displayNameAttr: 'cn', emailAttr: 'mail', bindDn: 'cn=svc,dc=corp', bindPasswordSet: true,
  updatedBy: 'admin', updatedAt: '2026-10-07T09:00:00Z' };

describe('LDAP settings', () => {
  it('tests the form as typed without sending the stored password', async () => {
    const tests: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/ldap'), () => HttpResponse.json(config)),
      http.post(apiUrl('/api/v1/admin/ldap/test'), async ({ request }) => {
        tests.push(await request.json());
        return HttpResponse.json({ ok: true });
      }),
    );
    renderApp('/admin/ldap');

    const userSearchBase = await screen.findByLabelText(tr.admin.ldap.fields.userSearchBase);
    await userEvent.clear(userSearchBase);
    await userEvent.type(userSearchBase, 'ou=staff');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.test }));

    expect(await screen.findByText(tr.admin.common.testOk)).toBeInTheDocument();
    expect(tests[0]).toMatchObject({ userSearchBase: 'ou=staff', url: 'ldaps://ldap.corp' });
    expect(tests[0]).not.toHaveProperty('bindPassword');
  });

  it('asks for the bind password again when the URL changes', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(http.get(apiUrl('/api/v1/admin/ldap'), () => HttpResponse.json(config)));
    renderApp('/admin/ldap');

    const url = await screen.findByLabelText(tr.admin.ldap.fields.url);
    await userEvent.clear(url);
    await userEvent.type(url, 'ldaps://other.corp');

    expect(await screen.findByText(tr.admin.secret.reenter(tr.admin.ldap.secretTarget))).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: tr.admin.common.save })).toBeDisabled());
    expect(screen.getByRole('button', { name: tr.admin.common.test })).toBeDisabled();
  });

  function ldapPuts(puts: unknown[], status = 200, body: unknown = config) {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/ldap'), () => HttpResponse.json(config)),
      http.put(apiUrl('/api/v1/admin/ldap'), async ({ request }) => {
        puts.push(await request.json());
        return HttpResponse.json(body as object, { status });
      }),
    );
  }

  it('omits the bind password on keep, sends the typed one on set and an empty one on clear', async () => {
    const puts: unknown[] = [];
    ldapPuts(puts);
    const router = renderApp('/admin/ldap');

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).not.toHaveProperty('bindPassword');

    await userEvent.type(await screen.findByLabelText(tr.admin.ldap.fields.bindPassword), 'typed-bind-pw');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1]).toHaveProperty('bindPassword', 'typed-bind-pw');
    await waitFor(() => expect(JSON.stringify(
      router.queryClient.getMutationCache().getAll().map((mutation) => mutation.state.variables ?? null))).not.toContain('typed-bind-pw'));

    await userEvent.click(await screen.findByRole('button', { name: tr.admin.secret.clear }));
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(puts).toHaveLength(3));
    expect(puts[2]).toHaveProperty('bindPassword', '');
  });

  it('shows the refusal to disable LDAP in the form', async () => {
    ldapPuts([], 409, { status: 409, detail: 'Disabling LDAP needs at least one active local admin' });
    renderApp('/admin/ldap');

    await userEvent.click(await screen.findByRole('switch'));
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));

    await waitFor(() => expect(document.querySelector('.mantine-Alert-root')).toHaveTextContent('Disabling LDAP needs at least one active local admin'));
  });
});
