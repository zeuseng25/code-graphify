import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router';

export type UrlValue = string | number | boolean | readonly string[] | null | undefined;

/** Screen state in the URL (web UI spec §4: linkable screens). A filter change returns to the first page. */
export function useUrlState() {
  const [params, setParams] = useSearchParams();
  // React Router's functional setParams does not queue, so updates of one tick build on each other here
  const pending = useRef<URLSearchParams | null>(null);
  useEffect(() => {
    pending.current = null;
  }, [params]);
  const raw = Number(params.get('page'));
  const page = Number.isInteger(raw) && raw > 0 ? raw : 0;

  function update(changes: Record<string, UrlValue>, keepPage = false) {
    const next = new URLSearchParams(pending.current ?? params);
    for (const [key, value] of Object.entries(changes)) {
      next.delete(key);
      if (value == null || value === '') {
        continue;
      }
      if (Array.isArray(value)) {
        value.filter((item) => item !== '').forEach((item) => next.append(key, item));
      } else {
        next.set(key, String(value));
      }
    }
    if (!keepPage && !('page' in changes)) {
      next.delete('page');
    }
    if (next.get('page') === '0') {
      next.delete('page');
    }
    pending.current = next;
    setParams(next);
  }

  return { params, page, update };
}
