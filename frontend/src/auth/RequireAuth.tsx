import { Center, Loader } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { ME_KEY } from '../api/queryClient';
import { ErrorView } from '../components/ErrorView';
import { useMe } from './session';

/** Pages behind sign-in; re-checks the session on every navigation so an expired one returns to sign-in. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const me = useMe();
  const location = useLocation();
  const client = useQueryClient();

  // the first mount has just loaded /me; only a later navigation re-checks it
  const checkedPath = useRef(location.pathname);
  useEffect(() => {
    if (checkedPath.current === location.pathname) {
      return;
    }
    checkedPath.current = location.pathname;
    void client.invalidateQueries({ queryKey: ME_KEY });
  }, [client, location.pathname]);

  if (me.isPending) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    );
  }
  if (me.isError && !me.data) {
    return <ErrorView error={me.error} onRetry={() => void me.refetch()} />;
  }
  if (!me.data) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  if (me.data.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}
