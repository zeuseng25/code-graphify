import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { server, apiUrl } from '../test/server';
import { api, call, resetCsrf, ApiError } from './client';

describe('api client', () => {
  beforeEach(() => resetCsrf());

  it('sends the CSRF token the server names on unsafe requests only', async () => {
    const seen: (string | null)[] = [];
    let csrfCalls = 0;
    server.use(
      http.get(apiUrl('/api/v1/auth/csrf'), () => {
        csrfCalls++;
        return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't1' });
      }),
      http.post(apiUrl('/api/v1/auth/logout'), ({ request }) => {
        seen.push(request.headers.get('X-XSRF-TOKEN'));
        return new HttpResponse(null, { status: 204 });
      }),
      http.get(apiUrl('/api/v1/auth/me'), ({ request }) => {
        seen.push(request.headers.get('X-XSRF-TOKEN'));
        return HttpResponse.json({ username: 'u', role: 'USER', mustChangePassword: false });
      }),
    );

    await call(api.POST('/api/v1/auth/logout'));
    await call(api.GET('/api/v1/auth/me'));
    await call(api.POST('/api/v1/auth/logout'));

    expect(seen).toEqual(['t1', null, 't1']);
    expect(csrfCalls).toBe(1);
  });

  it('refetches a rejected CSRF token once and retries', async () => {
    const tokens = ['old', 'new'];
    const seen: (string | null)[] = [];
    server.use(
      http.get(apiUrl('/api/v1/auth/csrf'), () =>
        HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: tokens.shift() })),
      http.post(apiUrl('/api/v1/auth/logout'), ({ request }) => {
        const token = request.headers.get('X-XSRF-TOKEN');
        seen.push(token);
        return token === 'new'
          ? new HttpResponse(null, { status: 204 })
          : HttpResponse.json({ status: 403, detail: 'Missing or invalid CSRF token', code: 'CSRF' }, { status: 403 });
      }),
    );

    await call(api.POST('/api/v1/auth/logout'));

    expect(seen).toEqual(['old', 'new']);
  });

  it('does not retry other 403s and reports the problem detail', async () => {
    let posts = 0;
    server.use(
      http.get(apiUrl('/api/v1/auth/csrf'), () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't' })),
      http.post(apiUrl('/api/v1/auth/logout'), () => {
        posts++;
        return HttpResponse.json({ status: 403, detail: 'Not allowed' }, { status: 403 });
      }),
    );

    const error = await call(api.POST('/api/v1/auth/logout')).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
    expect((error as ApiError).message).toBe('Not allowed');
    expect(posts).toBe(1);
  });

  it('loads one token for concurrent unsafe requests', async () => {
    let csrfCalls = 0;
    server.use(
      http.get(apiUrl('/api/v1/auth/csrf'), async () => {
        csrfCalls++;
        await new Promise((resolve) => setTimeout(resolve, 20));
        return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't' });
      }),
      http.post(apiUrl('/api/v1/auth/logout'), () => new HttpResponse(null, { status: 204 })),
    );

    await Promise.all([call(api.POST('/api/v1/auth/logout')), call(api.POST('/api/v1/auth/logout'))]);

    expect(csrfCalls).toBe(1);
  });

  it('refuses a token response that is not the backend\'s', async () => {
    server.use(http.get(apiUrl('/api/v1/auth/csrf'), () => HttpResponse.json({ unexpected: true })));

    await expect(call(api.POST('/api/v1/auth/logout'))).rejects.toBeInstanceOf(TypeError);
  });
});
