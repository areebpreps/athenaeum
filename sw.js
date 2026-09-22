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

// Requests that should always prefer the network, so a new index.html
// deploy shows up immediately instead of being stuck behind an old cache
// entry (cache-first would otherwise never re-check the network for these).
const NETWORK_FIRST = new Set(['./', './index.html']);

function shellKey(url) {
  const path = url.pathname.endsWith('/') ? './' : './' + url.pathname.split('/').pop();
  return path;
}

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
  const url = new URL(req.url);

  // Only handle same-origin GET requests for shell files. Everything else
  // (Supabase reads/writes, Gemini calls, CDN scripts, Google Fonts)
  // passes straight to the network untouched.
  if (req.method !== 'GET' || url.origin !== self.location.origin) {
    return;
  }

  // Treat any same-origin navigation (e.g. a deep link) as the HTML shell too.
  const isNavigation = req.mode === 'navigate';
  const key = shellKey(url);

  if (isNavigation || NETWORK_FIRST.has(key)) {
    // Network-first: always try to get the latest index.html. Only fall
    // back to the cached copy if the network is unreachable (offline).
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res && res.status === 200) {
            const copy = res.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put('./index.html', copy));
          }
          return res;
        })
        .catch(() => caches.match('./index.html'))
    );
    return;
  }

  // Everything else in the shell (icons, manifest): cache-first is fine
  // since these rarely change.
  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req)
        .then((res) => {
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
