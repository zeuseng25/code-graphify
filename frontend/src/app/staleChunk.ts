import { lazy, type ComponentType } from 'react';

/**
 * Lazily loaded code (the graph views) lives in content-hashed files that a redeploy replaces. A tab opened before
 * the redeploy still asks for the old file, gets a 404, and the import fails; reloading the page loads the new
 * version. The reload happens once: a second failure within RELOAD_GUARD_MS is a real outage and is shown as an error.
 */
const RELOAD_KEY = 'graphify-stale-chunk-reload';

/** Technical: a second stale-chunk reload within this many milliseconds is not attempted. */
export const RELOAD_GUARD_MS = 10_000;

/** The messages browsers give a failed dynamic import (Chrome, Safari, Firefox). */
const STALE_CHUNK_MESSAGES = [
  'Failed to fetch dynamically imported module',
  'Importing a module script failed',
  'error loading dynamically imported module',
];

export function isStaleChunkError(error: unknown): boolean {
  return error instanceof Error && STALE_CHUNK_MESSAGES.some((message) => error.message.includes(message));
}

/** Reloads the page unless it was just reloaded for the same reason; true when a reload was started. */
export function reloadOnce(reload: () => void = () => window.location.reload(), now: number = Date.now()): boolean {
  try {
    const last = Number(sessionStorage.getItem(RELOAD_KEY));
    if (last && now - last < RELOAD_GUARD_MS) {
      return false;
    }
    sessionStorage.setItem(RELOAD_KEY, String(now));
  } catch {
    return false; // without the guard a reload could loop
  }
  reload();
  return true;
}

/** React.lazy that reloads the page once when the code file is gone after a redeploy. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- the same constraint React.lazy places on its component
export function lazyWithReload<T extends ComponentType<any>>(load: () => Promise<{ default: T }>) {
  return lazy(() => load().catch((error: unknown) => {
    if (isStaleChunkError(error) && reloadOnce()) {
      return new Promise<never>(() => {}); // the page is reloading
    }
    throw error;
  }));
}
