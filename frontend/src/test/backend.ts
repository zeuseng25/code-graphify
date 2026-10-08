import { http, HttpResponse } from 'msw';
import { apiUrl, server } from './server';

export const testUser = {
  username: 'ayse', displayName: 'Ayşe', role: 'USER', source: 'LOCAL', mustChangePassword: false,
};

/** UI settings for tests; a short poll interval keeps polling tests fast. */
export const testUiConfig = {
  pageDefaultSize: 50, pageMaxSize: 500, graphMaxNodes: 500, impactDefaultDepth: 3, impactMaxDepth: 10,
  pollIntervalMillis: 20,
};

/** Registers a signed-in session (csrf, me, ui-config); tests add the screen's own handlers. */
export function signedIn(options: { user?: Partial<typeof testUser>; uiConfig?: Partial<typeof testUiConfig> } = {}) {
  const user = { ...testUser, ...options.user };
  const uiConfig = { ...testUiConfig, ...options.uiConfig };
  server.use(
    http.get(apiUrl('/api/v1/auth/csrf'), () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't' })),
    http.get(apiUrl('/api/v1/auth/me'), () => HttpResponse.json(user)),
    http.get(apiUrl('/api/v1/ui-config'), () => HttpResponse.json(uiConfig)),
  );
  return { user, uiConfig };
}
