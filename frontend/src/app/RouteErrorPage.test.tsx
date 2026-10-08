import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { tr } from '../i18n/tr';
import { RouteErrorPage } from './RouteErrorPage';

function renderFailing(error: unknown) {
  function Boom(): never {
    throw error;
  }
  const router = createMemoryRouter([{ path: '/', element: <Boom />, errorElement: <RouteErrorPage /> }]);
  vi.spyOn(console, 'error').mockImplementation(() => {});
  render(<MantineProvider><RouterProvider router={router} /></MantineProvider>);
}

describe('route error page', () => {
  it('a chunk missing after a redeploy asks for a reload, in Turkish', async () => {
    renderFailing(new TypeError('Failed to fetch dynamically imported module: https://x/assets/RepoGraphView-old.js'));

    expect(await screen.findByText(tr.errors.newVersion)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: tr.errors.reload })).toBeInTheDocument();
    expect(screen.queryByText(/Unexpected Application Error/)).not.toBeInTheDocument();
  });

  it('any other failure shows a Turkish notice with a reload', async () => {
    renderFailing(new Error('boom'));

    expect(await screen.findByText(tr.errors.unexpected)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: tr.errors.reload })).toBeInTheDocument();
  });
});
