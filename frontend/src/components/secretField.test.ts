import { expect, it } from 'vitest';
import { KEEP_SECRET, secretNeedsReentry, secretPayload } from './secret';

it('sends nothing to keep, an empty string to clear and the text to replace', () => {
  expect(secretPayload(KEEP_SECRET)).toBeUndefined();
  expect(secretPayload({ mode: 'clear' })).toBe('');
  expect(secretPayload({ mode: 'set', value: 's3cret' })).toBe('s3cret');
  expect(JSON.stringify({ name: 'x', secret: secretPayload(KEEP_SECRET) })).toBe('{"name":"x"}');
});

it('asks for the secret again only when a stored one would go to a changed target', () => {
  expect(secretNeedsReentry(true, true, KEEP_SECRET)).toBe(true);
  expect(secretNeedsReentry(true, false, KEEP_SECRET)).toBe(false);
  expect(secretNeedsReentry(false, true, KEEP_SECRET)).toBe(false);
  expect(secretNeedsReentry(true, true, { mode: 'set', value: 'new' })).toBe(false);
  expect(secretNeedsReentry(true, true, { mode: 'clear' })).toBe(false);
});

import { ldapTargetChanged, mavenTargetChanged, scmTargetChanged } from './secret';

it('compares targets like the backend: stripped, blank is absent, case-sensitive', () => {
  const scm = (baseUrl: string | null, username: string | null) => ({ baseUrl, username });
  expect(scmTargetChanged(scm('https://scm.x/', ' ci '), scm('https://scm.x', 'ci'))).toBe(false);
  expect(scmTargetChanged(scm('https://scm.x//', ''), scm('https://scm.x', null))).toBe(false);
  expect(scmTargetChanged(scm('https://scm.x', 'CI'), scm('https://scm.x', 'ci'))).toBe(true);
  const maven = (url: string, username: string | null) => ({ url, username });
  expect(mavenTargetChanged(maven('https://r/x/', 'a'), maven('https://r/x', 'a'))).toBe(true);
  expect(mavenTargetChanged(maven(' https://r/x ', ''), maven('https://r/x', null))).toBe(false);
  const ldap = (url: string, bindDn: string | null) => ({ url, bindDn });
  expect(ldapTargetChanged(ldap('ldap://h', 'cn=a'), ldap('ldap://h', 'cn=A'))).toBe(true);
  expect(ldapTargetChanged(ldap('ldap://h/', 'cn=a'), ldap('ldap://h', 'cn=a'))).toBe(true);
});
