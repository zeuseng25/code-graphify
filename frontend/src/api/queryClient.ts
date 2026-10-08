import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError } from './client';

/** The query key of the signed-in user (null when signed out). */
export const ME_KEY = ['me'] as const;

/** A 401 from any call means the session ended: forget the user, which sends RequireAuth to sign-in. */
export function createQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError: (error) => signedOutOn(error) }),
    mutationCache: new MutationCache({ onError: (error) => signedOutOn(error) }),
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  function signedOutOn(error: unknown) {
    if (error instanceof ApiError && error.status === 401) {
      client.setQueryData(ME_KEY, null);
    }
  }
  return client;
}
