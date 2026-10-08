import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useUiConfig } from '../config/UiConfigContext';
import { api, call } from './client';

/** Repositories for a filter list: the first page at the largest page size the backend allows. */
export function useRepositoryOptions() {
  const { pageMaxSize } = useUiConfig();
  return useQuery({
    queryKey: ['repository-options', pageMaxSize],
    queryFn: () => call(api.GET('/api/v1/repositories', { params: { query: { size: pageMaxSize } } })),
  });
}

export function useRepositories(q: string, status: string | undefined, page: number) {
  return useQuery({
    queryKey: ['repositories', q, status, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/repositories', { params: { query: { q: q || undefined, status, page } } })),
  });
}

export function useRepository(id: number) {
  return useQuery({
    queryKey: ['repository', id],
    queryFn: () => call(api.GET('/api/v1/repositories/{id}', { params: { path: { id } } })),
  });
}

export function useRepositoryRuns(id: number, page: number) {
  return useQuery({
    queryKey: ['repository-runs', id, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/repositories/{id}/runs', { params: { path: { id }, query: { page } } })),
  });
}
