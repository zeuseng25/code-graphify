import { Alert } from '@mantine/core';
import type { ReactNode } from 'react';
import { tr } from '../i18n/tr';
import { useMe, type Role } from './session';

/** Pages for one role only (web UI spec §4.2); the backend enforces the same rule on every call. */
export function RequireRole({ role, children }: { role: Role; children: ReactNode }) {
  const me = useMe().data;
  if (me?.role !== role) {
    return <Alert color="red">{tr.admin.forbidden}</Alert>;
  }
  return <>{children}</>;
}
