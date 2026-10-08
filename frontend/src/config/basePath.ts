/** The application root as an absolute URL without a trailing slash, from the <base href> the backend writes. */
export function appRoot(): string {
  return new URL('.', document.baseURI).href.replace(/\/$/, '');
}

/** The router basename: the root's path ("/graphify"), or undefined when the app is at the server root. */
export function routerBasename(): string | undefined {
  const path = new URL(`${appRoot()}/`).pathname.replace(/\/$/, '');
  return path === '' ? undefined : path;
}
