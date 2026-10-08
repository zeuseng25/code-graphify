import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';

// public/color-scheme.js runs from index.html before the bundle, so a dark page never paints white first
const script = readFileSync(resolve(__dirname, '../../public/color-scheme.js'), 'utf8');

function run(systemDark: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: systemDark && query === '(prefers-color-scheme: dark)' }));
  new Function(script)();
  return document.documentElement.getAttribute('data-mantine-color-scheme');
}

afterEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute('data-mantine-color-scheme');
  vi.unstubAllGlobals();
});

describe('color scheme before the first paint', () => {
  it('follows the system when nothing was chosen', () => {
    expect(run(true)).toBe('dark');
    expect(run(false)).toBe('light');
  });

  it('a remembered choice wins over the system', () => {
    localStorage.setItem('mantine-color-scheme-value', 'light');
    expect(run(true)).toBe('light');
    localStorage.setItem('mantine-color-scheme-value', 'dark');
    expect(run(false)).toBe('dark');
  });

  it('"auto" and unknown values follow the system', () => {
    localStorage.setItem('mantine-color-scheme-value', 'auto');
    expect(run(true)).toBe('dark');
    localStorage.setItem('mantine-color-scheme-value', 'purple');
    expect(run(false)).toBe('light');
  });
});
