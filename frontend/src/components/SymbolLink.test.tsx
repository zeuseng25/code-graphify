import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { SymbolLink } from './SymbolLink';

it('a long signature may wrap at its dots and words, keeping its accessible name', () => {
  render(
    <MantineProvider>
      <MemoryRouter>
        <SymbolLink symbol={{ id: 7, key: 'a.B#createBuilder()', display: 'ZeusInitializer.createBuilder()' }} />
      </MemoryRouter>
    </MantineProvider>,
  );

  const link = screen.getByRole('link', { name: 'ZeusInitializer.createBuilder()' });
  expect(link).toHaveAttribute('href', '/symbols/7');
  expect(link.querySelectorAll('wbr').length).toBeGreaterThan(0);
});
