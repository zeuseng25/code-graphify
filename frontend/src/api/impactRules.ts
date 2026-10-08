import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import type { components } from './schema';

export type EntryPointAnnotation = components['schemas']['EntryPointAnnotation'];
export type AnnotationChange = components['schemas']['AnnotationChange'];
export type ImpactRule = components['schemas']['ImpactRule'];

const ENTRY_POINTS_KEY = ['entry-points'] as const;
const RULES_KEY = ['impact-rules'] as const;

export function useEntryPoints() {
  return useQuery({
    queryKey: ENTRY_POINTS_KEY,
    queryFn: () => call(api.GET('/api/v1/admin/entry-point-annotations')),
  });
}

/** Create (id null) or full update of one annotation. */
export function useSaveEntryPoint(id: number | null) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: AnnotationChange) => id == null
      ? call(api.POST('/api/v1/admin/entry-point-annotations', { body }))
      : call(api.PUT('/api/v1/admin/entry-point-annotations/{id}', { params: { path: { id } }, body })),
    onSuccess: () => notifySuccess(tr.admin.common.saved),
    onError: notifyError,
    onSettled: () => client.invalidateQueries({ queryKey: ENTRY_POINTS_KEY }),
  });
}

export function useDeleteEntryPoint() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => call(api.DELETE('/api/v1/admin/entry-point-annotations/{id}', { params: { path: { id } } })),
    onSuccess: () => notifySuccess(tr.admin.common.deleted),
    onError: notifyError,
    onSettled: () => client.invalidateQueries({ queryKey: ENTRY_POINTS_KEY }),
  });
}

export function useImpactRules() {
  return useQuery({
    queryKey: RULES_KEY,
    queryFn: () => call(api.GET('/api/v1/admin/impact-rules')),
  });
}

export function useSaveImpactRule() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ kind, propagates, shownAtLevel1 }: { kind: NonNullable<ImpactRule['kind']> } & components['schemas']['RuleChange']) =>
      call(api.PUT('/api/v1/admin/impact-rules/{kind}', { params: { path: { kind } }, body: { propagates, shownAtLevel1 } })),
    onSuccess: () => notifySuccess(tr.admin.common.saved),
    onError: notifyError,
    onSettled: () => client.invalidateQueries({ queryKey: RULES_KEY }),
  });
}
