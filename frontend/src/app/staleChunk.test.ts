import { afterEach, describe, expect, it, vi } from 'vitest';
import { isStaleChunkError, reloadOnce, RELOAD_GUARD_MS } from './staleChunk';

afterEach(() => sessionStorage.clear());

describe('a chunk missing after a redeploy', () => {
  it('reloads the page once to pick up the new version', () => {
    const reload = vi.fn();
    expect(reloadOnce(reload, 1_000)).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('does not reload again right after a reload, so a real outage shows an error instead of looping', () => {
    const reload = vi.fn();
    reloadOnce(reload, 1_000);
    expect(reloadOnce(reload, 1_000 + RELOAD_GUARD_MS - 1)).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(reloadOnce(reload, 1_000 + RELOAD_GUARD_MS + 1)).toBe(true);
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it('never reloads when the guard cannot be stored', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    const reload = vi.fn();
    expect(reloadOnce(reload, 1_000)).toBe(false);
    expect(reload).not.toHaveBeenCalled();
    vi.restoreAllMocks();
  });

  it('recognises the browsers\' failed-chunk errors and nothing else', () => {
    expect(isStaleChunkError(new TypeError('Failed to fetch dynamically imported module: https://x/assets/A-1.js'))).toBe(true);
    expect(isStaleChunkError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isStaleChunkError(new TypeError('error loading dynamically imported module'))).toBe(true);
    expect(isStaleChunkError(new Error('boom'))).toBe(false);
    expect(isStaleChunkError('Failed to fetch dynamically imported module')).toBe(false);
  });
});
