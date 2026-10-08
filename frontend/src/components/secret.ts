/** A secret input in a full update (spec §4.3): keep the stored value, clear it, or replace it. */
export type SecretState = { mode: 'keep' } | { mode: 'clear' } | { mode: 'set'; value: string };

export const KEEP_SECRET: SecretState = { mode: 'keep' };

/** The request value: absent keeps the stored secret, "" clears it, text replaces it. */
export function secretPayload(state: SecretState): string | undefined {
  switch (state.mode) {
    case 'keep':
      return undefined;
    case 'clear':
      return '';
    case 'set':
      return state.value;
  }
}

/**
 * The backend keeps a stored secret only for the same target and answers 400 otherwise; the form says so before
 * sending (the backend still enforces it).
 */
export function secretNeedsReentry(stored: boolean, targetChanged: boolean, state: SecretState): boolean {
  return stored && targetChanged && state.mode === 'keep';
}

type Text = string | null | undefined;

/** The backend's sameTarget normalisation: strip, blank means absent. Case-sensitive. */
export function normalizeTarget(value: Text): string | null {
  const stripped = (value ?? '').trim();
  return stripped === '' ? null : stripped;
}

/** Repo connection (Bitbucket, GitHub, Git) base URLs also lose trailing slashes (ScmConnectionAdministration). */
export function normalizeScmBaseUrl(value: Text): string | null {
  const normalized = normalizeTarget(value);
  return normalized === null ? null : normalized.replace(/\/+$/, '');
}

function differs(pairs: Array<[string | null, string | null]>): boolean {
  return pairs.some(([current, stored]) => current !== stored);
}

/** Repo connection: the target is baseUrl + username. */
export function scmTargetChanged(form: { baseUrl: Text; username: Text }, view: { baseUrl: Text; username: Text }): boolean {
  return differs([
    [normalizeScmBaseUrl(form.baseUrl), normalizeScmBaseUrl(view.baseUrl)],
    [normalizeTarget(form.username), normalizeTarget(view.username)],
  ]);
}

/** Maven repository: the target is url + username (the url keeps a trailing slash, as the backend does). */
export function mavenTargetChanged(form: { url: Text; username: Text }, view: { url: Text; username: Text }): boolean {
  return differs([
    [normalizeTarget(form.url), normalizeTarget(view.url)],
    [normalizeTarget(form.username), normalizeTarget(view.username)],
  ]);
}

/** LDAP: the target is url + bindDn. */
export function ldapTargetChanged(form: { url: Text; bindDn: Text }, view: { url: Text; bindDn: Text }): boolean {
  return differs([
    [normalizeTarget(form.url), normalizeTarget(view.url)],
    [normalizeTarget(form.bindDn), normalizeTarget(view.bindDn)],
  ]);
}
