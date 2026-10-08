import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import type { GraphQuery } from '../features/graph/graphParams';
import { tr } from '../i18n/tr';
import { ApiError, api, call, type Problem } from './client';
import { filenameOf, isBlob, saveBlob } from './download';
import { notifyError } from './notify';

export function useRepoGraph(id: number, query: GraphQuery) {
  return useQuery({
    queryKey: ['repo-graph', id, query],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/repositories/{id}/graph', {
      params: { path: { id }, query: { level: query.level, focus: query.focus, includeExternal: query.includeExternal } },
    })),
  });
}

export function useRepoGraphReport(id: number) {
  return useQuery({
    queryKey: ['repo-graph-report', id],
    queryFn: () => call(api.GET('/api/v1/repositories/{id}/graph/report', { params: { path: { id } } })),
  });
}

export type GraphExportFormat = 'graphml' | 'json';

async function exportRepoGraph(id: number, format: GraphExportFormat, query: GraphQuery): Promise<void> {
  const { data, error, response } = await api.GET('/api/v1/repositories/{id}/graph/export', {
    params: { path: { id }, query: { format, level: query.level, focus: query.focus, includeExternal: query.includeExternal } },
    parseAs: 'blob',
  });
  if (!response.ok || !isBlob(data)) {
    throw new ApiError(response.status, (typeof error === 'object' && error !== null ? error : {}) as Problem);
  }
  // with no level in the URL the backend shows its default level; name the file after the graph, not an empty segment
  const level = (query.level ?? 'graph').toLowerCase();
  saveBlob(data, filenameOf(response.headers.get('Content-Disposition')) ?? tr.graph.exportFileName(level, format));
}

/** GraphML/JSON of the graph the URL describes; a mutation, so a 401 signs the user out like any other call. */
export function useExportRepoGraph(id: number) {
  return useMutation({
    mutationFn: ({ format, query }: { format: GraphExportFormat; query: GraphQuery }) => exportRepoGraph(id, format, query),
    onError: notifyError,
  });
}
