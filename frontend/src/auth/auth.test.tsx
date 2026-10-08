import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../i18n/tr';
import { apiUrl, server } from '../test/server';
import { renderApp } from '../test/renderApp';

const user = { username: 'ayse', displayName: 'Ayşe', role: 'USER', source: 'LOCAL', mustChangePassword: false };
const uiConfig = {
  pageDefaultSize: 50, pageMaxSize: 500, graphMaxNodes: 500, impactDefaultDepth: 3, impactMaxDepth: 10,
  pollIntervalMillis: 5000,
};

function backend(options: { signedIn: boolean; me?: typeof user; loginError?: string }) {
  let signedIn = options.signedIn;
  let me = options.me ?? user;
  server.use(
    http.get(apiUrl('/api/v1/auth/csrf'), () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't' })),
    http.get(apiUrl('/api/v1/auth/me'), () =>
      signedIn ? HttpResponse.json(me) : HttpResponse.json({ status: 401, detail: 'Authentication is required' }, { status: 401 })),
    http.post(apiUrl('/api/v1/auth/login'), () => {
      if (options.loginError) {
        return HttpResponse.json({ status: 401, detail: options.loginError }, { status: 401 });
      }
      signedIn = true;
      return HttpResponse.json(me);
    }),
    http.post(apiUrl('/api/v1/auth/change-password'), () => {
      me = { ...me, mustChangePassword: false };
      return HttpResponse.json(me);
    }),
    http.get(apiUrl('/api/v1/repositories'), () => HttpResponse.json({ items: [], page: 0, size: 500, total: 0 })),
    http.get(apiUrl('/api/v1/ui-config'), () =>
      signedIn ? HttpResponse.json(uiConfig) : HttpResponse.json({ status: 401 }, { status: 401 })),
  );
  return { expire: () => { signedIn = false; } };
}

async function signIn() {
  await userEvent.type(await screen.findByLabelText(tr.login.username), 'ayse');
  await userEvent.type(screen.getByLabelText(tr.login.password), 'secret-pw');
  await userEvent.click(screen.getByRole('button', { name: tr.login.submit }));
}

describe('sign-in', () => {
  it('sends a signed-out visitor to sign-in and back to the page they asked for', async () => {
    backend({ signedIn: false });
    const router = renderApp('/somewhere?x=1');

    await signIn();

    await waitFor(() => expect(router.state.location.pathname).toBe('/somewhere'));
    expect(router.state.location.search).toBe('?x=1');
    expect(await screen.findByText(tr.errors.notFound)).toBeInTheDocument();
  });

  it('ignores an off-site next', async () => {
    backend({ signedIn: false });
    const router = renderApp('/login?next=%2F%2Fevil.example%2Fx');

    await signIn();

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(await screen.findByRole('heading', { name: tr.search.title })).toBeInTheDocument();
  });

  it('shows the backend message when sign-in fails', async () => {
    backend({ signedIn: false, loginError: 'Invalid username or password' });
    renderApp('/');

    await signIn();

    expect(await screen.findByText('Invalid username or password')).toBeInTheDocument();
  });

  it('says the server is unreachable when the network fails', async () => {
    backend({ signedIn: false });
    server.use(http.post(apiUrl('/api/v1/auth/login'), () => HttpResponse.error()));
    renderApp('/');

    await signIn();

    expect(await screen.findByText(tr.errors.unreachable)).toBeInTheDocument();
  });

  it('shows a Turkish message for a server failure without a problem body', async () => {
    backend({ signedIn: false });
    server.use(http.post(apiUrl('/api/v1/auth/login'), () =>
      new HttpResponse('<html>Bad Gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } })));
    renderApp('/');

    await signIn();

    expect(await screen.findByText(tr.errors.server)).toBeInTheDocument();
    expect(screen.queryByText(/HTTP 502|Bad Gateway/)).not.toBeInTheDocument();
  });

  it('shows the password page while the backend withholds the UI settings', async () => {
    backend({ signedIn: true, me: { ...user, mustChangePassword: true } });
    server.use(http.get(apiUrl('/api/v1/ui-config'), () =>
      HttpResponse.json({ status: 403, detail: 'Password change required', code: 'PASSWORD_CHANGE_REQUIRED' }, { status: 403 })));
    renderApp('/');

    expect(await screen.findByText(tr.changePassword.mustChange)).toBeInTheDocument();
    expect(screen.queryByText(tr.errors.forbidden)).not.toBeInTheDocument();
    expect(screen.queryByText('Password change required')).not.toBeInTheDocument();
  });

  it('makes a user who must change the password do so first', async () => {
    backend({ signedIn: true, me: { ...user, mustChangePassword: true } });
    const router = renderApp('/');

    expect(await screen.findByText(tr.changePassword.mustChange)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(tr.changePassword.current), 'old-pw');
    await userEvent.type(screen.getByLabelText(tr.changePassword.next), 'New-Password-1');
    await userEvent.type(screen.getByLabelText(tr.changePassword.confirm), 'New-Password-1');
    await userEvent.click(screen.getByRole('button', { name: tr.changePassword.submit }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(await screen.findByRole('heading', { name: tr.search.title })).toBeInTheDocument();
  });

  it('a later 401 returns to sign-in', async () => {
    const session = backend({ signedIn: true });
    const router = renderApp('/');
    expect(await screen.findByRole('heading', { name: tr.search.title })).toBeInTheDocument();

    session.expire();
    router.navigate('/elsewhere');

    expect(await screen.findByLabelText(tr.login.username)).toBeInTheDocument();
    expect(router.state.location.search).toContain(encodeURIComponent('/elsewhere'));
  });

  it('shows the signed-in user and no admin menu to a USER', async () => {
    backend({ signedIn: true });
    renderApp('/');

    expect(await screen.findByText('Ayşe')).toBeInTheDocument();
    expect(screen.getByText(tr.header.roles.USER)).toBeInTheDocument();
    expect(screen.queryByText(tr.nav.admin)).not.toBeInTheDocument();
  });
});
