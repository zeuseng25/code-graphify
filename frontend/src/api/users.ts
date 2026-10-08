import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import { ME_KEY } from './queryClient';
import type { components } from './schema';

export type AppUser = components['schemas']['AppUser'];
export type UserRole = NonNullable<AppUser['role']>;

export const ROLE_OPTIONS = (Object.keys(tr.header.roles) as UserRole[]).map((value) => ({ value, label: tr.header.roles[value] }));

const USERS = ['users'] as const;

/** Prefix of the directory-search keys; an LDAP config change invalidates it. */
export const DIRECTORY_KEY = ['directory'] as const;

export function useUsers(q: string, page: number) {
  return useQuery({
    queryKey: [...USERS, q, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/admin/users', { params: { query: { q: q || undefined, page } } })),
  });
}

/** Role and active changes refetch the list even when refused, so the row shows the server's truth again. */
function useUserChange<T>(change: (args: T) => Promise<unknown>, done: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: change,
    onSuccess: () => notifySuccess(done),
    onError: notifyError,
    // an admin may have changed their own role or state
    onSettled: () => Promise.all([
      client.invalidateQueries({ queryKey: USERS }),
      client.invalidateQueries({ queryKey: ME_KEY }),
    ]),
  });
}

export function useChangeRole() {
  return useUserChange(({ id, role }: { id: number; role: UserRole }) =>
    call(api.PUT('/api/v1/admin/users/{id}/role', { params: { path: { id } }, body: { role } })), tr.admin.users.roleChanged);
}

export function useChangeActive() {
  return useUserChange(({ id, active }: { id: number; active: boolean }) =>
    call(api.PUT('/api/v1/admin/users/{id}/active', { params: { path: { id } }, body: { active } })), tr.admin.common.saved);
}

export function useDirectorySearch(q: string) {
  return useQuery({
    queryKey: [...DIRECTORY_KEY, q],
    enabled: q.trim() !== '',
    queryFn: () => call(api.GET('/api/v1/admin/ldap/users', { params: { query: { q: q.trim() } } })),
  });
}

export function useRegisterUser() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { username: string; role: UserRole }) => call(api.POST('/api/v1/admin/users', { body })),
    onSuccess: () => notifySuccess(tr.admin.users.added),
    onError: notifyError,
    onSettled: () => Promise.all([
      client.invalidateQueries({ queryKey: USERS }),
      client.invalidateQueries({ queryKey: DIRECTORY_KEY }),
    ]),
  });
}
