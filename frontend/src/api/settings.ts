import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifySuccess } from './notify';
import type { components } from './schema';

export type Setting = components['schemas']['Setting'];

const KEY = ['settings'] as const;

export function useSettings() {
  return useQuery({ queryKey: KEY, queryFn: () => call(api.GET('/api/v1/admin/settings')) });
}

/** Saves one setting; the row shows a refusal under its field, and a saved value refreshes the UI settings. */
export function useSaveSetting(key: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (value: string) => call(api.PUT('/api/v1/admin/settings/{key}', { params: { path: { key } }, body: { value } })),
    // no notifyError on purpose: a refusal is shown under the field by the row
    onSuccess: () => {
      notifySuccess(tr.admin.common.saved);
      return Promise.all([
        client.invalidateQueries({ queryKey: KEY }),
        client.invalidateQueries({ queryKey: ['ui-config'] }),
      ]);
    },
  });
}
