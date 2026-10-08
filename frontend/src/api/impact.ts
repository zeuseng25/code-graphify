import { useMutation, useQuery } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { ApiError, api, call, type Problem } from './client';
import { notifyError } from './notify';
import { filenameOf, isBlob, saveBlob } from './download';
import type { ImpactRequest } from '../features/impact/impactParams';

/** POST /impact as a query: the analysis is idempotent, so a shared link re-runs it (web UI spec §4.1 row 5). */
export function useImpact(request: ImpactRequest | null) {
  return useQuery({
    queryKey: ['impact', request],
    enabled: request !== null,
    // an analysis is a pure function of its request: never re-run it on focus, reconnect or remount
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    queryFn: () => call(api.POST('/api/v1/impact', { body: request! })),
  });
}

/** CSV of the same analysis, saved under the name the backend suggests. */
async function exportImpactCsv(request: ImpactRequest): Promise<void> {
  const { data, error, response } = await api.POST('/api/v1/impact/export', {
    params: { query: { format: 'csv' } },
    body: request,
    parseAs: 'blob',
  });
  if (!response.ok || !isBlob(data)) {
    throw new ApiError(response.status, (typeof error === 'object' && error !== null ? error : {}) as Problem);
  }
  saveBlob(data, filenameOf(response.headers.get('Content-Disposition')) ?? tr.impact.csvFileName);
}

/** The export as a mutation, so a 401 signs the user out like any other call; other failures are notified. */
export function useExportImpactCsv() {
  return useMutation({ mutationFn: exportImpactCsv, onError: notifyError });
}
