// Athenaeum service worker — Phase 5.1
// Only runs in a browser context (GitHub Pages). The Android app loads the
// app bundled locally via file:///android_asset/, where service workers
// cannot register at all, so this file has no effect inside the APK.
//
// Scope: cache the app shell (this page + its icons/manifest) so the app
// still opens offline. Everything else (Supabase, Gemini, CDN libraries,
// Google Fonts) is left to the network — caching a live database or a
// third-party CDN library here would risk serving stale or broken code.

const CACHE_NAME = 'athenaeum-shell-v1';
const SHELL_FILES = [
  './',
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './icon-512-maskable.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(SHELL_FILES))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) =>
      Promise.all(
        names
          .filter((name) => name !== CACHE_NAME)
          .map((name) => caches.delete(name))
      )
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;

  // Only handle same-origin GET requests for shell files. Everything else
  // (Supabase reads/writes, Gemini calls, CDN scripts, Google Fonts)
  // passes straight to the network untouched.
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) {
    return;
  }

  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req)
        .then((res) => {
          // Opportunistically cache newly-seen same-origin shell assets.
          if (res && res.status === 200) {
            const copy = res.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
          }
          return res;
        })
        .catch(() => caches.match('./index.html'));
    })
  );
});
