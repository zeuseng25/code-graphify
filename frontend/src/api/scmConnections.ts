import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import type { components } from './schema';

export type ScmConnectionView = components['schemas']['ScmConnectionView'];
export type ScmConnectionUpdate = components['schemas']['ScmConnectionUpdate'];

const LIST_KEY = ['scm-connections'] as const;

export function useScmConnections(enabled = true) {
  return useQuery({
    queryKey: LIST_KEY,
    enabled,
    queryFn: () => call(api.GET('/api/v1/admin/scm-connections')),
  });
}

export function useScmConnection(id: number | null) {
  return useQuery({
    queryKey: ['scm-connection', id],
    enabled: id != null,
    queryFn: () => call(api.GET('/api/v1/admin/scm-connections/{id}', { params: { path: { id: id! } } })),
  });
}

/** Create (id null) or full update; the secret field follows secretPayload (absent keeps the stored one). */
export function useSaveScmConnection(id: number | null) {
  const client = useQueryClient();
  return useMutation({
    // the typed token lives only as long as the request; the page also reset()s after a success
    gcTime: 0,
    mutationFn: (body: ScmConnectionUpdate) => id == null
      ? call(api.POST('/api/v1/admin/scm-connections', { body }))
      : call(api.PUT('/api/v1/admin/scm-connections/{id}', { params: { path: { id } }, body })),
    onSuccess: (saved) => {
      void client.invalidateQueries({ queryKey: LIST_KEY });
      client.setQueryData(['scm-connection', saved?.id], saved);
      notifySuccess(tr.admin.common.saved);
    },
    onError: notifyError,
  });
}

export function useDeleteScmConnection(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.DELETE('/api/v1/admin/scm-connections/{id}', { params: { path: { id } } })),
    onSuccess: () => {
      client.removeQueries({ queryKey: ['scm-connection', id] });
      void client.invalidateQueries({ queryKey: LIST_KEY });
      notifySuccess(tr.admin.common.deleted);
    },
    onError: notifyError,
  });
}

/** Tests the saved configuration; the result (and the updated last-test fields) are refetched. */
export function useTestScmConnection(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/api/v1/admin/scm-connections/{id}/test', { params: { path: { id } } })),
    onSuccess: () => notifySuccess(tr.admin.common.testOk),
    onError: notifyError,
    onSettled: () => Promise.all([
      client.invalidateQueries({ queryKey: ['scm-connection', id] }),
      client.invalidateQueries({ queryKey: LIST_KEY }),
    ]),
  });
}
