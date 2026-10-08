import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';
import react from '@vitejs/plugin-react';

/** The dev server serves the app at "/"; in the WAR the backend writes the real context path into <base>. */
function devBaseHref(): Plugin {
  return {
    name: 'graphify-dev-base-href',
    apply: 'serve',
    transformIndexHtml: (html) => html.replace(/<base href="[^"]*"\s*\/?>/, '<base href="/" />'),
  };
}

export default defineConfig({
  // relative asset URLs, resolved against <base href>, so the build works under any context path
  base: './',
  plugins: [react(), devBaseHref()],
  server: {
    // developer convenience only: where `npm run dev` forwards API calls (GRAPHIFY_API overrides it)
    proxy: { '/api': process.env.GRAPHIFY_API ?? 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: 'http://localhost/graphify/' } },
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
