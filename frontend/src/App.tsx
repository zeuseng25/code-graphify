import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { createQueryClient } from './api/queryClient';
import { routerBasename } from './config/basePath';
import { routes } from './routes';
import { cssVariablesResolver, theme } from './theme/theme';

const router = createBrowserRouter(routes, { basename: routerBasename() });

export function App() {
  const [queryClient] = useState(createQueryClient);
  return (
    <MantineProvider defaultColorScheme="auto" theme={theme} cssVariablesResolver={cssVariablesResolver}>
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>
  );
}
