import { Center, Loader } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, type ReactNode } from 'react';
import { api, call } from '../api/client';
import type { components } from '../api/schema';
import { ErrorView } from '../components/ErrorView';

export type UiConfig = components['schemas']['UiConfig'];

const UiConfigContext = createContext<UiConfig | null>(null);

/** Loads the UI settings (GET /ui-config) once per session and provides them to every page. */
export function UiConfigProvider({ children }: { children: ReactNode }) {
  const config = useQuery({ queryKey: ['ui-config'], queryFn: () => call(api.GET('/api/v1/ui-config')) });
  if (config.isPending) {
    return (
      <Center p="xl">
        <Loader />
      </Center>
    );
  }
  if (config.isError && !config.data) {
    return <ErrorView error={config.error} onRetry={() => void config.refetch()} />;
  }
  return <UiConfigContext.Provider value={config.data}>{children}</UiConfigContext.Provider>;
}

export function useUiConfig(): UiConfig {
  const config = useContext(UiConfigContext);
  if (!config) {
    throw new Error('useUiConfig outside UiConfigProvider');
  }
  return config;
}
