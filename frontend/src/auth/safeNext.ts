/** Control characters and whitespace anywhere: browsers strip some of them from a URL, so "/\t/evil" becomes "//evil". */
// eslint-disable-next-line no-control-regex -- matching control characters is the purpose
const UNSAFE_CHARACTERS = /[\u0000-\u001F\u007F\s]/;

/** Where to go after sign-in: only a path inside this app, never another site (no open redirect). */
export function safeNext(next: string | null): string {
  if (!next || UNSAFE_CHARACTERS.test(next) || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) {
    return '/';
  }
  return new URL(next, window.location.origin).origin === window.location.origin ? next : '/';
}
