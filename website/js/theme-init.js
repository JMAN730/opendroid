// Runs synchronously before first paint to avoid a flash of the wrong
// theme. Kept in its own tiny file (rather than inline) so the site's
// Content-Security-Policy can use a strict script-src 'self' without
// 'unsafe-inline'.
document.documentElement.setAttribute('data-theme', localStorage.getItem('opendroid-theme') || 'dark');
