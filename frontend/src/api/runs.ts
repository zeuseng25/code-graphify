import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { useUiConfig } from '../config/UiConfigContext';
import { tr } from '../i18n/tr';
import { api, call } from './client';
import { notifyError, notifySuccess } from './notify';
import type { components } from './schema';

export function useRuns(page: number) {
  return useQuery({
    queryKey: ['runs', page],
    placeholderData: keepPreviousData,
    queryFn: () => call(api.GET('/api/v1/index/runs', { params: { query: { page } } })),
  });
}

/** One run; while it is RUNNING it is refreshed every ui.poll_interval (web UI spec §4.1 row 8). */
export function useRun(id: number) {
  const { pollIntervalMillis } = useUiConfig();
  return useQuery({
    queryKey: ['run', id],
    queryFn: () => call(api.GET('/api/v1/index/runs/{runId}', { params: { path: { runId: id } } })),
    refetchInterval: (query) => (query.state.data?.run?.status === 'RUNNING' ? pollIntervalMillis : false),
  });
}

export type StartRequest = components['schemas']['StartRequest'];

/** Starts a run and opens it; the caller shows a 409 (another run in progress) itself. */
export function useStartRun() {
  const client = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: (request: StartRequest) => call(api.POST('/api/v1/index/runs', { body: request })),
    onSuccess: (started) => {
      void client.invalidateQueries({ queryKey: ['runs'] });
      if (started?.runId != null) {
        navigate(`/runs/${started.runId}`);
      }
    },
  });
}

export function useCancelRun(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/api/v1/index/runs/{runId}/cancel', { params: { path: { runId: id } } })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['run', id] });
      void client.invalidateQueries({ queryKey: ['runs'] });
      notifySuccess(tr.admin.runs.cancelRequested);
    },
    onError: notifyError,
  });
}
