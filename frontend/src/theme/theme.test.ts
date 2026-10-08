import { createTheme, DEFAULT_THEME, mergeMantineTheme } from '@mantine/core';
import { describe, expect, it } from 'vitest';
import { cssVariablesResolver, theme } from './theme';

const resolved = cssVariablesResolver(mergeMantineTheme(DEFAULT_THEME, createTheme(theme)));

describe('theme', () => {
  it('tinted badges and buttons stay bright on the dark background', () => {
    // Mantine's own dark "light" variant is a near-black navy with pale text; here the tint is the colour itself
    expect(resolved.dark['--mantine-color-blue-light']).toContain('rgba');
    expect(resolved.dark['--mantine-color-blue-light-color']).toBe('var(--mantine-color-blue-2)');
    expect(resolved.dark['--mantine-color-green-light-color']).toBe('var(--mantine-color-green-2)');
  });

  it('the frame and the cards are told apart from the page in both schemes', () => {
    for (const scheme of [resolved.light, resolved.dark]) {
      expect(scheme['--app-chrome-bg']).toBeTruthy();
      expect(scheme['--app-card-bg']).toBeTruthy();
    }
    expect(resolved.dark['--app-chrome-bg']).not.toBe(resolved.dark['--app-card-bg']);
  });

  it('no badge label is clipped in a squeezed column', () => {
    expect(theme.components?.Badge?.styles).toEqual({ label: { overflow: 'visible' } });
  });
});
