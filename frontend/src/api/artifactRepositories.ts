import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import type { components } from './schema';

export type ArtifactRepositoryView = components['schemas']['ArtifactRepositoryView'];
export type ArtifactRepositoryUpdate = components['schemas']['ArtifactRepositoryUpdate'];

const LIST_KEY = ['artifact-repositories'] as const;
const DETAIL_KEY = 'artifact-repository';

export function useArtifactRepositories(enabled = true) {
  return useQuery({
    queryKey: LIST_KEY,
    enabled,
    queryFn: () => call(api.GET('/api/v1/admin/artifact-repositories')),
  });
}

export function useArtifactRepository(id: number | null) {
  return useQuery({
    queryKey: [DETAIL_KEY, id],
    enabled: id != null,
    queryFn: () => call(api.GET('/api/v1/admin/artifact-repositories/{id}', { params: { path: { id: id! } } })),
  });
}

/** Create (id null) or full update; the secret field follows secretPayload (absent keeps the stored one). */
export function useSaveArtifactRepository(id: number | null) {
  const client = useQueryClient();
  return useMutation({
    // the typed secret lives only as long as the request; the page also reset()s after a success
    gcTime: 0,
    mutationFn: (body: ArtifactRepositoryUpdate) => id == null
      ? call(api.POST('/api/v1/admin/artifact-repositories', { body }))
      : call(api.PUT('/api/v1/admin/artifact-repositories/{id}', { params: { path: { id } }, body })),
    onSuccess: (saved) => {
      void client.invalidateQueries({ queryKey: LIST_KEY });
      client.setQueryData([DETAIL_KEY, saved?.id], saved);
      notifySuccess(tr.admin.common.saved);
    },
    onError: notifyError,
  });
}

export function useDeleteArtifactRepository(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.DELETE('/api/v1/admin/artifact-repositories/{id}', { params: { path: { id } } })),
    onSuccess: () => {
      client.removeQueries({ queryKey: [DETAIL_KEY, id] });
      void client.invalidateQueries({ queryKey: LIST_KEY });
      notifySuccess(tr.admin.common.deleted);
    },
    onError: notifyError,
  });
}

/** Tests the saved configuration; the result (and the updated last-test fields) are refetched. */
export function useTestArtifactRepository(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/api/v1/admin/artifact-repositories/{id}/test', { params: { path: { id } } })),
    onSuccess: () => notifySuccess(tr.admin.common.testOk),
    onError: notifyError,
    onSettled: () => Promise.all([
      client.invalidateQueries({ queryKey: [DETAIL_KEY, id] }),
      client.invalidateQueries({ queryKey: LIST_KEY }),
    ]),
  });
}
