/* Kinship service worker.
 *
 * Strategies follow docs/mobile_optimization.md §4:
 *   - app shell: precache, versioned, old caches removed on activate
 *   - GET /v1/activities*: stale-while-revalidate, 5 minute freshness
 *   - GET /v1/groups/{id} and /v1/events/{id}: network-first, 30 second freshness,
 *     cached copy used only when offline (read-only)
 *   - hashed Next.js assets and icons: cache-first
 *   - auth, profile, invitations, match jobs, messages and every mutation:
 *     network-only, never stored, so private group data and tokens are not
 *     available through the cache.
 *
 * The queue for offline actions lives in IndexedDB (lib/outbox.ts), not here,
 * because actions are replayed with a fresh access token after reconnect.
 */

const VERSION = "v1";
const SHELL_CACHE = `kinship-shell-${VERSION}`;
const DATA_CACHE = `kinship-data-${VERSION}`;
const STATIC_CACHE = `kinship-static-${VERSION}`;

const SHELL_ASSETS = [
  "/",
  "/offline.html",
  "/manifest.webmanifest",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/icon-512-maskable.png",
];

const DISCOVERY_TTL_MS = 5 * 60 * 1000;
const DETAIL_TTL_MS = 30 * 1000;
const IMAGE_TTL_MS = 7 * 24 * 60 * 60 * 1000;

/** Paths that must never be written to any cache. */
const PRIVATE_PATTERNS = [
  /^\/v1\/auth\//,
  /^\/v1\/users\//,
  /^\/v1\/interests/,
  /^\/v1\/realtime\//,
  /^\/v1\/match/,
  /^\/v1\/ws/,
  /\/messages$/,
];

function isPrivatePath(pathname) {
  return PRIVATE_PATTERNS.some((pattern) => pattern.test(pathname));
}

function isDiscovery(pathname) {
  return pathname === "/v1/activities" || pathname.startsWith("/v1/activities/");
}

function isGroupOrEventDetail(pathname) {
  if (pathname.startsWith("/v1/events/")) {
    return true;
  }
  // /v1/groups/{id} only; list, joins, membership and messages are excluded.
  return /^\/v1\/groups\/[^/]+$/.test(pathname);
}

function offlineJson() {
  return new Response(
    JSON.stringify({ error: { code: "OFFLINE", message: "You are offline and this resource is not cached." } }),
    { status: 503, headers: { "Content-Type": "application/json" } },
  );
}

async function cacheTimestamp(cache, request) {
  const cached = await cache.match(request);
  if (!cached) {
    return { cached: null, age: Infinity };
  }
  const stored = cached.headers.get("x-kinship-cached-at");
  const age = stored ? Date.now() - Number(stored) : Infinity;
  return { cached, age };
}

async function putWithTimestamp(cache, request, response) {
  const headers = new Headers(response.headers);
  headers.set("x-kinship-cached-at", String(Date.now()));
  const body = await response.clone().blob();
  await cache.put(
    request,
    new Response(body, { status: response.status, statusText: response.statusText, headers }),
  );
}

async function staleWhileRevalidate(request) {
  const cache = await caches.open(DATA_CACHE);
  const { cached } = await cacheTimestamp(cache, request);
  const network = fetch(request)
    .then(async (response) => {
      if (response.ok) {
        await putWithTimestamp(cache, request, response.clone());
      }
      return response;
    })
    .catch(() => null);
  if (cached) {
    return cached;
  }
  const response = await network;
  return response || offlineJson();
}

async function networkFirstDetail(request) {
  const cache = await caches.open(DATA_CACHE);
  const { cached, age } = await cacheTimestamp(cache, request);
  // The app fetches with cache: "no-store" whenever it needs authoritative data
  // (after a mutation or a query invalidation). Such requests always revalidate,
  // so a cached group/event cannot mask a just-created occurrence or RSVP; the
  // cached copy is still used when the network is unavailable.
  const wantsFresh = request.cache === "no-store";
  if (!wantsFresh && cached && age < DETAIL_TTL_MS) {
    return cached;
  }
  try {
    const response = await fetch(request);
    if (response.ok) {
      await putWithTimestamp(cache, request, response.clone());
    }
    return response;
  } catch (error) {
    return cached || offlineJson();
  }
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  if (cached) {
    return cached;
  }
  const response = await fetch(request);
  if (response.ok) {
    await cache.put(request, response.clone());
  }
  return response;
}

async function navigationResponse(request) {
  const cache = await caches.open(SHELL_CACHE);
  try {
    const response = await fetch(request);
    if (response.ok && response.type === "basic") {
      await cache.put(request, response.clone());
    }
    return response;
  } catch (error) {
    const cached = (await cache.match(request)) || (await cache.match("/"));
    return cached || (await cache.match("/offline.html"));
  }
}

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(SHELL_CACHE);
      await cache.addAll(SHELL_ASSETS);
      await self.skipWaiting();
    })(),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const keep = new Set([SHELL_CACHE, DATA_CACHE, STATIC_CACHE]);
      const names = await caches.keys();
      await Promise.all(
        names
          .filter((name) => name.startsWith("kinship-") && !keep.has(name))
          .map((name) => caches.delete(name)),
      );
      await self.clients.claim();
    })(),
  );
});

self.addEventListener("message", (event) => {
  const data = event.data || {};
  if (data.type === "PURGE_DATA") {
    // Logout / account switch: drop every response that belonged to a session.
    event.waitUntil(
      (async () => {
        await caches.delete(DATA_CACHE);
        await caches.delete(STATIC_CACHE);
      })(),
    );
  }
  if (data.type === "SKIP_WAITING") {
    self.skipWaiting();
  }
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") {
    return;
  }
  const url = new URL(request.url);

  if (url.origin !== self.location.origin) {
    // Only the API origin is handled; everything else is left to the network.
    if (isPrivatePath(url.pathname)) {
      return;
    }
    if (isDiscovery(url.pathname)) {
      event.respondWith(staleWhileRevalidate(request));
      return;
    }
    if (isGroupOrEventDetail(url.pathname)) {
      event.respondWith(networkFirstDetail(request));
    }
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith(navigationResponse(request));
    return;
  }
  if (isPrivatePath(url.pathname)) {
    return;
  }
  if (url.pathname.startsWith("/_next/static/") || url.pathname.startsWith("/icons/")) {
    event.respondWith(cacheFirst(request, STATIC_CACHE));
    return;
  }
  if (url.pathname.startsWith("/manifest.webmanifest") || url.pathname === "/offline.html") {
    event.respondWith(cacheFirst(request, SHELL_CACHE));
    return;
  }
  if (/\.(png|jpg|jpeg|webp|avif|svg|gif)$/.test(url.pathname)) {
    event.respondWith(cacheFirst(request, STATIC_CACHE));
  }
});
