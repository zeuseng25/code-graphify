import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it } from 'vitest';
import { tr } from '../i18n/tr';
import { ColorSchemeMenu } from './ColorSchemeMenu';

afterEach(() => localStorage.clear());

const t = tr.header.theme;

// the menu opens with a transition; under a loaded test run that can take longer than the default 1s wait
const MENU_WAIT = { timeout: 5_000 };

async function choose(label: string) {
  render(<MantineProvider defaultColorScheme="auto"><ColorSchemeMenu /></MantineProvider>);
  await userEvent.click(screen.getByRole('button', { name: `${t.label}: ${t.auto}` }));
  await userEvent.click(await screen.findByRole('menuitem', { name: label }, MENU_WAIT));
}

it('the theme follows the system until one is chosen, and a dark choice is remembered', async () => {
  await choose(t.dark);

  expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'dark');
  expect(screen.getByRole('button', { name: `${t.label}: ${t.dark}` })).toBeInTheDocument();
  expect(localStorage.getItem('mantine-color-scheme-value')).toBe('dark');
});

it('a light choice turns the page light whatever the system uses', async () => {
  await choose(t.light);

  expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'light');
  expect(localStorage.getItem('mantine-color-scheme-value')).toBe('light');
});

it('the open menu marks the scheme in use', async () => {
  render(<MantineProvider defaultColorScheme="auto"><ColorSchemeMenu /></MantineProvider>);
  await userEvent.click(screen.getByRole('button', { name: `${t.label}: ${t.auto}` }));

  // a tick for the eye, "(seçili)" for a screen reader; read in one pass while the dropdown is open
  const items = await screen.findAllByRole('menuitem', {}, MENU_WAIT);
  const byScheme = (label: string) => items.find((item) => item.textContent?.startsWith(label))!;
  expect(byScheme(t.auto)).toHaveTextContent(t.current);
  expect(byScheme(t.auto)).toHaveTextContent(`(${t.selected})`);
  expect(byScheme(t.dark)).not.toHaveTextContent(t.current);
  expect(byScheme(t.dark)).not.toHaveTextContent(t.selected);
});
