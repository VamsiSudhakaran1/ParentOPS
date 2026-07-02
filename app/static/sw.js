// Minimal service worker: network-first, needed for installability.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(clients.claim()));
self.addEventListener('fetch', (e) => {
  e.respondWith(fetch(e.request).catch(() =>
    new Response('<h2 style="font-family:sans-serif;text-align:center;margin-top:40vh">Offline — reconnect to sync</h2>',
      { headers: { 'Content-Type': 'text/html' } })));
});
