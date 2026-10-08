/* global window, document */
// Applies the remembered colour scheme (or the system's) before the first paint, so a dark page never flashes white.
// Loaded from index.html ahead of the bundle; the CSP allows only same-origin scripts, so it is a file, not inline.
// Mantine keeps the choice under this key and takes over once the app has loaded.
(function () {
  try {
    var stored = window.localStorage.getItem('mantine-color-scheme-value');
    var scheme = stored === 'light' || stored === 'dark'
      ? stored
      : (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-mantine-color-scheme', scheme);
  } catch {
    // storage or matchMedia unavailable: Mantine applies the scheme once it loads
  }
})();
