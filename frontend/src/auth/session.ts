import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, call, resetCsrf } from '../api/client';
import { ME_KEY } from '../api/queryClient';
import type { components } from '../api/schema';

export type Me = components['schemas']['Me'];
export type Role = NonNullable<Me['role']>;

/** The signed-in user, or null when signed out. */
export function useMe() {
  return useQuery({
    queryKey: ME_KEY,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await call(api.GET('/api/v1/auth/me'));
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          return null;
        }
        throw error;
      }
    },
  });
}

export function useLogin() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (credentials: { username: string; password: string }) =>
      call(api.POST('/api/v1/auth/login', { body: credentials })),
    // a new session: nothing cached from before sign-in (or from another user) may survive
    onSuccess: (me) => {
      resetCsrf();
      client.clear();
      client.setQueryData(ME_KEY, me);
    },
    gcTime: 0,
  });
}

export function useLogout() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/api/v1/auth/logout')),
    onSettled: () => {
      resetCsrf();
      client.clear();
      client.setQueryData(ME_KEY, null);
    },
  });
}

export function useChangePassword() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (change: { currentPassword: string; newPassword: string }) =>
      call(api.POST('/api/v1/auth/change-password', { body: change })),
    onSuccess: (me) => {
      resetCsrf();
      client.setQueryData(ME_KEY, me);
    },
    gcTime: 0,
  });
}

/** Whether the signed-in user is an admin; the backend enforces the same rule on every admin call. */
export function useIsAdmin(): boolean {
  return useMe().data?.role === 'ADMIN';
}
