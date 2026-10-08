import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, call } from './client';
import type { components } from './schema';

export type SymbolHit = components['schemas']['SymbolHit'];
export type SymbolKind = NonNullable<SymbolHit['kind']>;
export type UsageView = components['schemas']['UsageView'];
export type Confidence = NonNullable<UsageView['confidence']>;
export type UsageKind = NonNullable<UsageView['kind']>;

export function useSymbolSearch(q: string, kind: SymbolKind | undefined, repo: number | undefined, page: number) {
  const text = q.trim();
  return useQuery({
    queryKey: ['symbol-search', text, kind, repo, page],
    enabled: text !== '',
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/symbols/search', { params: { query: { q: text, kind, repo, page } } })),
  });
}

export function useSymbol(id: number) {
  return useQuery({
    queryKey: ['symbol', id],
    queryFn: () => call(api.GET('/api/v1/symbols/{id}', { params: { path: { id } } })),
  });
}

export function useUsageSummary(id: number) {
  return useQuery({
    queryKey: ['usage-summary', id],
    queryFn: () => call(api.GET('/api/v1/symbols/{id}/usages/summary', { params: { path: { id } } })),
  });
}

export interface UsageFilters {
  confidence: Confidence[];
  kind: UsageKind[];
  repo?: number;
}

export function useUsages(id: number, filters: UsageFilters, page: number) {
  return useQuery({
    queryKey: ['usages', id, filters, page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/symbols/{id}/usages', {
      params: {
        path: { id },
        query: {
          confidence: filters.confidence.length ? filters.confidence : undefined,
          kind: filters.kind.length ? filters.kind : undefined,
          repo: filters.repo,
          page,
        },
      },
    })),
  });
}
