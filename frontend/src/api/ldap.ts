import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import type { components } from './schema';
import { DIRECTORY_KEY } from './users';

export type LdapConfigView = components['schemas']['LdapConfigView'];
export type LdapConfigUpdate = components['schemas']['LdapConfigUpdate'];

const KEY = ['ldap-config'] as const;

export function useLdapConfig() {
  return useQuery({ queryKey: KEY, queryFn: () => call(api.GET('/api/v1/admin/ldap')) });
}

export function useSaveLdapConfig() {
  const client = useQueryClient();
  return useMutation({
    // the typed bind password lives only as long as the request; the page also reset()s after a success
    gcTime: 0,
    mutationFn: (body: LdapConfigUpdate) => call(api.PUT('/api/v1/admin/ldap', { body })),
    onSuccess: (saved) => {
      client.setQueryData(KEY, saved);
      void client.invalidateQueries({ queryKey: DIRECTORY_KEY });
      notifySuccess(tr.admin.common.saved);
    },
    onError: notifyError,
  });
}

/** Tests the form as typed (not saved); a missing password is merged from the stored one by the backend. */
export function useTestLdapConfig() {
  return useMutation({
    gcTime: 0,
    mutationFn: (body: LdapConfigUpdate) => call(api.POST('/api/v1/admin/ldap/test', { body })),
    onSuccess: () => notifySuccess(tr.admin.common.testOk),
    onError: notifyError,
  });
}
