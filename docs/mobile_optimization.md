# Mobile-Web Optimization & PWA
## Kinship — Low-Latency, Seamless Experience

Version: 1.1 | Status: Reviewed for implementation | Applies to: `web/` (Next.js 14 App Router)

This document previously lived inside `ci_integration.txt` by mistake. It is the
frontend performance contract for Phase 5.

---

## 1. Targets (measured on a throttled 4G mobile profile)

| Metric | Target |
|---|---|
| Largest Contentful Paint (LCP) | < 2.5 s |
| Interaction to Next Paint (INP) | < 200 ms |
| Cumulative Layout Shift (CLS) | < 0.1 |
| Lighthouse mobile performance | ≥ 90 |
| Discovery API p95, cache hit | < 50 ms (server) |
| WS round-trip p95 | < 150 ms |
| Discovery bundle (first load JS) | < 150 KB gzip |

Until Phase 5, these are goals, not verified numbers. Record the measured values
in `PHASE_5.md` and `docs/perf/baseline.md`.

---

## 2. Critical Rendering Path

| Technique | Implementation | Effect |
|---|---|---|
| Server rendering | App Router server components render the discovery shell; cards stream via Suspense | Faster FCP, SEO-safe markup |
| Route splitting | Dynamic import map, chat, event editor, match flow | Discovery route stays small |
| Skeleton screens | Card and group skeletons matching final layout | Perceived latency ≈ 0, CLS stable |
| Image handling | Next `<Image>` with responsive `sizes`, WebP/AVIF, blur placeholder, lazy below the fold | 40–60 % fewer bytes |
| Fonts | System font stack or one variable font, subset, `font-display: swap` | No render-blocking font |
| Icons | Inline SVG sprite for the critical set | No icon-font request |

---

## 3. Network Efficiency

| Technique | Implementation |
|---|---|
| Compression | Brotli/gzip for JSON and HTML > 1 KB |
| ETag + 304 | Discovery responses carry a strong `ETag`; client sends `If-None-Match` |
| Payload shaping | Discovery card already contains `next_event`, `member_count`, `group_name` (no client joins) |
| Cursor pagination | `page_token` / `before_seq`; never offset |
| Prefetch | Prefetch group detail on card focus/touchstart, deduplicated by TanStack Query |
| Request cancellation | `AbortController` on filter changes and unmount |
| Image proxy | Same-origin `/images/*` route caches and resizes upstream images |

---

## 4. Service Worker Strategy

### 4.1 Precaching (install)
App shell HTML, critical CSS, subset font, manifest, offline fallback page,
icons used above the fold. Version the cache with the build id; delete old caches
on `activate`.

### 4.2 Runtime caching

| Resource | Strategy | TTL / fallback |
|---|---|---|
| `/v1/activities*` | Stale-while-revalidate | 5 min; serve cached list offline |
| `/v1/groups/*` | Network-first | 30 s; cached group page offline (read-only) |
| `/images/*` | Cache-first | 7 days |
| Hashed static assets | Cache-first | Immutable |
| Auth, RSVP, messages | Network-only | Never cached; show offline error |

### 4.3 Offline behaviour

- Banner: "You're offline — showing cached activities".
- Cached discovery and group pages are read-only; RSVP, join and send are disabled
  with an explanation rather than failing silently.
- If Background Sync is unavailable, queue at most 20 actions in IndexedDB and
  flush on the `online` event; drop after 24 h and surface a count.
- Never present stale RSVP counts as authoritative; refresh on reconnect.

---

## 5. Interaction Optimizations

| Technique | Implementation |
|---|---|
| Optimistic RSVP | Update the button/count immediately; roll back with a toast on `409` |
| Optimistic send | Show a pending bubble keyed by `client_msg_id`; replace on `ack`; retry on reconnect |
| Virtualised chat | `@tanstack/react-virtual`, newest-first data, maintain scroll anchor |
| Reconnect | Exponential backoff 1→30 s with jitter; REST backfill after `ready` |
| Debounced filters | 300 ms debounce plus `AbortController` |
| Passive scroll | Passive listeners; no layout reads in scroll handlers |
| Touch targets | ≥ 44 × 44 px; visible focus rings; reduced-motion respected |

---

## 6. PWA Manifest (essentials)

~~~json
{
  "name": "Kinship",
  "short_name": "Kinship",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#ffffff",
  "theme_color": "#1a1a1a",
  "icons": [
    { "src": "/icons/192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "/icons/512.png", "sizes": "512x512", "type": "image/png" }
  ]
}
~~~

Install prompts are not required for the MVP; the manifest and service worker must
still pass Lighthouse's installability checks.

---

## 7. Measurement

1. Lighthouse mobile (throttled 4G, 4× CPU) on `/` and a group page; save JSON to
   `docs/perf/lighthouse-mobile.json`.
2. Playwright trace on the five E2E flows; check for layout shift and long tasks.
3. Bundle budget enforced in CI: `next build` output must keep first-load JS under
   150 KB gzip for the discovery route.
4. Re-run after any change to discovery payload shape; it is the largest response.
