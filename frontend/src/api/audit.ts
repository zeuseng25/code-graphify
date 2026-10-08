import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, call } from './client';
import type { components } from './schema';

export interface AuditFilters {
  actor?: string;
  action?: components['schemas']['AuditAction'];
  /** ISO-8601 instants, as the backend expects. */
  from?: string;
  to?: string;
}

export function useAudit(filters: AuditFilters, page: number) {
  return useQuery({
    queryKey: ['audit', filters, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/admin/audit', { params: { query: { ...filters, page } } })),
  });
}
