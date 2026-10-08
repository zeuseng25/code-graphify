import { alpha, Badge, createTheme, type CSSVariablesResolver } from '@mantine/core';

/**
 * The app's Mantine theme: the defaults with the colour adjustments below. A badge label is never clipped: Mantine
 * hides its overflow, which lets a squeezed table column cut "BAŞARILI" down to "BA…".
 */
export const theme = createTheme({
  components: {
    Badge: Badge.extend({ styles: { label: { overflow: 'visible' } } }),
  },
});

/** Presentational: how strongly a tinted ("light") badge or button shows its colour on the dark background. */
const DARK_TINT = 0.22;
const DARK_TINT_HOVER = 0.32;

/**
 * Colour adjustments per scheme. Mantine draws a tinted badge or button on the dark background as a near-black navy
 * with pale text, so status and role colours read as grey; here the tint is the colour itself at a low alpha with a
 * bright text shade. The header and menu (`--app-chrome-bg`) and the cards (`--app-card-bg`) get their own tones, so
 * the page reads in layers rather than as one flat surface.
 */
export const cssVariablesResolver: CSSVariablesResolver = (resolved) => {
  const tints: Record<string, string> = {};
  for (const [name, shades] of Object.entries(resolved.colors)) {
    if (name === 'dark') {
      continue;
    }
    tints[`--mantine-color-${name}-light`] = alpha(shades[6], DARK_TINT);
    tints[`--mantine-color-${name}-light-hover`] = alpha(shades[6], DARK_TINT_HOVER);
    tints[`--mantine-color-${name}-light-color`] = `var(--mantine-color-${name}-2)`;
  }
  return {
    variables: {},
    light: {
      '--app-chrome-bg': 'var(--mantine-color-gray-0)',
      '--app-card-bg': 'var(--mantine-color-white)',
    },
    dark: {
      ...tints,
      '--app-chrome-bg': 'var(--mantine-color-dark-8)',
      '--app-card-bg': 'var(--mantine-color-dark-6)',
    },
  };
};
