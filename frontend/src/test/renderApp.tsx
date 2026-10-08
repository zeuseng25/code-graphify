import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { createQueryClient } from '../api/queryClient';
import { resetCsrf } from '../api/client';
import { routes } from '../routes';

/** Renders the whole app at an in-app path with a fresh cache and CSRF token. */
export function renderApp(path: string) {
  resetCsrf();
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const queryClient = createQueryClient();
  render(
    <MantineProvider>
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>,
  );
  return Object.assign(router, { queryClient });
}
