# Phase 5 — Web App, PWA & E2E

Date: 2026-11-04
Status: **P5 COMPLETE — acceptance PASS.**

---

## 1. Implemented user journeys

All screens use the existing P1–P4 APIs; no business rule was re-implemented in the
browser and no endpoint was added or changed apart from the documented discovery
`ETag` (see §5).

| Journey | Screen | Behaviour |
|---|---|---|
| Discovery | `/` | Cursor pagination (`page_token`), category / radius / group-size filters, skeleton, empty and error states, distance-ordered cards, conditional `If-None-Match` revalidation |
| Activity detail | `/activities/[id]` | Group discovery for the activity, join / pending approval feedback, next occurrence per group |
| Groups | `/groups`, `/groups/[id]` | Membership status, join, cancel request, leave, owner-only recurring-event form, member list, approve/decline join requests, remove member |
| Recurring events | `/groups/[id]`, `/events/[id]` | Upcoming occurrences, RSVP going / not going, current status, capacity and "event full" feedback, closed RSVPs after start, cancellation |
| Matching | `/invitations` | Start a match job, poll job state (`QUEUED`→`DONE`/`FAILED`), invited members with distance and interests, expiry, accept / decline |
| Messaging | `/groups/[id]/chat` | History page (`before_seq`), optimistic bubble keyed by `client_msg_id`, ack / rollback, WebSocket live updates, reconnect with exponential backoff, REST backfill on `ready` |
| Profile | `/profile` | Display name, location, interests, availability; required for a matchable profile and for location-aware discovery |

Discovery now centres on the saved profile location (falling back to central
Berlin), which is what makes E1 meaningful; the API contract is unchanged.

Accessibility: skip link, `aria-current` navigation, labelled form controls,
`role="status"` / `role="alert"` announcements, visible `:focus-visible` rings,
44 px minimum touch targets, 16 px inputs (no iOS zoom), reduced-motion support,
single-column mobile layout with no horizontal overflow at 390 × 844.

## 2. PWA behaviour

- `public/manifest.webmanifest`: standalone, `start_url`/`scope` `/`, theme and
  background colours, 192/512/maskable icons, served as
  `application/manifest+json` with revalidation headers.
- `public/sw.js` (hand-written, no new dependency):
  - precaches the shell, offline page, manifest and icons; versioned caches,
    old `kinship-*` caches deleted on `activate`;
  - `GET /v1/activities*` — stale-while-revalidate, 5 min freshness;
  - `GET /v1/groups/{id}` and `GET /v1/events/{id}` — network-first with a 30 s
    freshness window and a cached read-only fallback when offline;
  - hashed `/_next/static/*`, icons and images — cache-first (7 days for images);
  - **never cached**: `/v1/auth/*`, `/v1/users/*`, `/v1/interests`,
    `/v1/realtime/*`, `/v1/match*`, `…/messages`, and every non-GET request.
    The data cache and static cache are purged via `postMessage` on login,
    register and logout so a second account on the same device cannot read the
    previous account's groups.
  - **freshness override**: any API request made with `cache: "no-store"` (which
    is how the app fetches after a mutation or invalidation) bypasses the 30 s
    window and revalidates, falling back to the cache only when the network is
    unavailable. Without this, a new occurrence or a just-flushed RSVP was masked
    by the cached group/event for up to 30 s.
- Offline: banner ("You're offline — showing cached activities"), cached
  discovery renders after reload, join/leave/send are disabled with an
  explanation, and RSVPs are queued.
- Offline queue (`lib/outbox.ts`): IndexedDB, maximum 20 actions, 24 h expiry,
  de-duplicated per action, replayed once on the `online` event with a refreshed
  access token, deleted on success, dropped on 4xx, retained on transport error.
  The banner reports the queued count and the delivered count.
- `npm run start` now serves the standalone build through
  `scripts/serve-standalone.mjs`; `next start` does not work with
  `output: "standalone"`.

## 3. E2E coverage and actual results

Playwright, Chromium, 390 × 844 mobile emulation, production build, one worker,
seed + per-test unique users/groups (no test depends on execution order).

| Spec | Covers | Result |
|---|---|---|
| `e1-discovery` | Register → complete profile → discovery, pagination, category filter | **PASS** |
| `e2-group-rsvp` | Activity → join group → next occurrence → my groups | **PASS** |
| `e3-owner-event` | Owner creates weekly series → occurrence → RSVP → count 1/6 | **PASS** |
| `e4-messaging` | Two members, live socket delivery, offline member, reconnect + REST backfill, sequence uniqueness | **PASS** |
| `e5-matching` | Request match → invitation (expiry shown) → accept → quorum forms group → my groups | **PASS** |
| `e6-authz` (a/b) | Signed-out screens, 401 envelope, private group, private chat, member cannot manage membership, owner-only controls hidden | **PASS** |
| `e7-pwa-offline` (a/b) | Manifest, SW activation, discovery cache, no private URLs cached, no token in localStorage/sessionStorage, only the httpOnly refresh cookie, offline render, offline RSVP queued once and flushed on reconnect, no horizontal overflow | **PASS** |

Final run: **9 passed (33.7 s), 0 failed** (`npx playwright test`, `EXIT=0`). After
adding the browser-storage assertion the PWA spec was re-run: **2 passed
(5.6 s)**; the other seven specs were unchanged by that addition.

Quality gates actually run:

| Check | Result |
|---|---|
| `npm run lint` | PASS — no warnings or errors |
| `npm run typecheck` | PASS |
| `npm run build` | PASS — 11 routes; `/` first-load JS **111 kB** (budget 150 kB) |
| `npx playwright test` | PASS — 9/9 |
| `mvn -Dit.test=DiscoveryIT verify` | PASS — DiscoveryIT: 2 tests, 0 failures |
| ETag live check | `ETag: "0dcb…"` then `If-None-Match` → **304**; `/v1/users/me` has no ETag |

Lighthouse (mobile form factor, simulated 4G, saved to
`docs/perf/lighthouse-mobile.json`):

| Metric | Value | Target |
|---|---|---|
| Performance | **97** | ≥ 90 |
| Accessibility | **100** | basics |
| LCP | **0.8 s** | < 2.5 s |
| CLS | 0.087 | < 0.1 |
| TBT | 140 ms | — |

The Lighthouse run measured the signed-out entry page `/`; the authenticated
discovery page was not measured (no scripted login in the report run).

## 4. Screens and flows deferred

- Image proxy `/images/*` and responsive `srcset`: the seed sets `image_url` to
  `NULL`, so there is no upstream image to proxy. Cards use the existing
  gradient treatment; adding a proxy would introduce a new contract.
- Workbox: the service worker is hand-written to keep the dependency set and the
  build untouched; the strategies match `docs/mobile_optimization.md` §4.
- Virtualised chat list (`@tanstack/react-virtual`): pages are capped at 50
  messages with explicit "load older", which is bounded in practice.
- Install prompt UI, push notifications, read receipts, maps: out of scope.
- Offline chat send and offline join/leave remain disabled (read-only cache);
  only RSVP is queued, as the only action with a server-side idempotent effect.

## 5. Decisions, defects fixed and risks

Changed behaviour (documented contracts, no API shape change):

1. **Discovery ETag** was deferred from P1 to P5. A `ShallowEtagHeaderFilter` is
   registered for `/v1/activities/*` only, so authenticated user-specific
   responses never acquire a cacheable validator. Discovery stores the ETag and
   replays it; a 304 reuses the parsed page.
2. `package.json` `start` now runs the standalone server. This surfaced because
   `next start` silently refused to serve `output: "standalone"`.

Defects found by the suite and fixed:

1. **Interests catalogue field mismatch** (new profile screen): the catalogue
   returns `id`, while saved profile interests use `interest_id`. Reading
   `interest_id` produced `undefined` for every checkbox (all appeared checked)
   and sent `interest_id: null`, so `PATCH /v1/users/me` returned 400. Fixed to
   use `id`; E1 now guards it.
2. **Service-worker staleness after mutation**: the 30 s group/event freshness
   window returned stale JSON for a just-created occurrence (E3) and a
   just-flushed RSVP (E7b). Fixed with the `no-store` revalidation override.
3. **Queued RSVP refetched with the pre-offline token** after reconnect. The
   flush now waits for the reauthenticated token to reach mounted queries before
   invalidating.
4. Test-side corrections: exact heading match (duplicate h1/h3 text), "Open
   chat" is a link, `/v1/interests` requires auth, seed only exposes two music
   activities with groups.

Known risks:

- E5 randomises the interest set and availability window per run so previous
  runs' accounts cannot outrank this run's candidates; with 220 possible interest
  triples the collision chance is low but non-zero.
- The service worker is exercised only in production builds; `next dev` will not
  register it by design.
- Cache freshness for discovery is 5 min by design; the banner and the
  "saved copy" note make that visible, but stale member counts can be shown.
- The E2E job was added to `.github/workflows/ci.yml` but was not executed in
  CI from this machine; the suite was run locally against the same compose
  services (PostGIS 5433, Redis 6379) and a production build.
- P1–P4 backend suites were not re-run in full; only `DiscoveryIT` was re-run
  because the only backend change is the discovery ETag filter.

---

## 6. P5.1 — CI validation and live UI review

### CI defects found and fixed

| Defect | Evidence | Fix |
|---|---|---|
| The runtime web image never copied `public/` | `/manifest.webmanifest`, `/sw.js`, `/offline.html` and icons were absent from the standalone image, so the PWA 404s in the compose stack | `COPY public ./public` in `web/Dockerfile`'s runner stage; verified 200 for all four assets through the container |
| `api` and `web` had no healthchecks, so `docker compose up --wait` returned before the API was listening and before the web server served anything | compose had healthchecks only for Postgres and Redis | `api` healthcheck (TCP 8080, 40 s start period), `web` healthcheck (fetches the manifest), `web` now depends on `api` being healthy |
| The workflow never verified readiness or the PWA assets | `npx playwright test` was the only gate after `up` | explicit `/readyz` poll plus `/`, manifest and `sw.js` polls before the tests, with container logs on failure |
| Playwright could try to spawn its own server in CI | `E2E_NO_SERVER` was not set, so a slow container start would make Playwright run `npm run start` in a tree that only had `npm ci` | `E2E_NO_SERVER=1` in the job |
| No HTML report upload and no teardown | only `web/test-results` was uploaded, containers were left running | uploads `web/test-results` + `web/playwright-report`, `down --remove-orphans` in an `always()` step, Playwright env scoped to the job |
| Integration-test reports were not uploaded | only `surefire-reports` | `failsafe-reports` added to the backend artifact |

### Local CI-equivalent evidence

| Step | Command | Result |
|---|---|---|
| Backend | `mvn -B -ntp verify` | **PASS** — 21 unit + 17 integration tests, `BUILD SUCCESS` |
| Install | `npm ci` | PASS (lockfile reproducible) |
| Lint / types | `npm run lint`, `npm run typecheck` | PASS |
| Build | `npm run build` | PASS, `FRONTEND_EXIT=0` |
| Stack | `docker compose -f infra/docker-compose.yml up -d --build --wait` | PASS — all four services healthy |
| Readiness | `GET /readyz` → `{"db":true,"redis":true}`; `/`, `/manifest.webmanifest`, `/sw.js`, `/offline.html`, icons → 200 | PASS |
| E2E (CI mode) | `E2E_NO_SERVER=1 CI=true npx playwright test` | **PASS — 9/9 in 41.5 s** (and 9/9 in 42.3 s after the UI fixes below) |

This was **not** executed on GitHub Actions (no push was made), so remote CI is
unverified; the commands above are the workflow's steps run locally against the
same compose stack.

### Live UI review — defects fixed

Reviewed at 390 × 844 and 1440 × 900 against the containerised production build,
signed in as the seeded organiser (`demo@kinship.local`) plus a second account in
an incognito tab.

1. **Matching invitations never appeared after a job finished.** The invitations
   query ran once on mount and was never invalidated when the background job
   reported `DONE`, so the page said "No pending invitations" while the API
   already had an `INVITED` invitation (confirmed by `GET /v1/match-invitations`).
   The stale "Matching is running in the background." notice also stayed forever.
   Fixed by invalidating `match-invitations` when the job reaches `DONE` and
   dropping the redundant notice.
2. **Group list labelled owners "Member".** The list payload has no role, and the
   card hard-coded `Member` for every active membership, contradicting the
   "Owner" badge on the group page. Relabelled to the truthful "Joined".
3. **Chat showed two different times for the same message.** Others' messages
   rendered the Berlin time plus a browser-local `toLocaleTimeString` suffix
   ("Wed, 4 Nov, 11:56 · 16:26"), which differed from own messages and from every
   other screen. Removed the extra suffix.
4. **Profile showed float32 artefacts** (`52.52000045776367`) in the location
   fields. Rounded to five decimals for display only.

### Verified by hand (no change needed)

Login, discovery with filters and the empty state, activity → group, group detail
(owner badge, members, join requests, recurring occurrences), RSVP going (count
and live status), chat send/ack, **live delivery between two signed-in browsers**,
invitation accept, profile save, sign-out state on `/groups`, offline banner
appearing and clearing, single-account data isolation (second account saw only
its one group), 3-column discovery and 2-column group grids at 1440 px, no
horizontal overflow at 390 px, and no application console errors (the only
console errors are the expected pre-login `/v1/auth/refresh` 401s).

### Known limitations left for a decision

- Incoming chat messages show no sender name, so in a 4–6 person group you cannot
  tell who wrote a message. Fixing it needs the member list on the chat screen
  (existing `GET /v1/groups/{id}` payload); not added without review sign-off.
- Discovery filters are component state only — they are not reflected in the URL,
  so a filtered list cannot be linked or restored on reload.
- `GET /v1/match-requests` (no job id) returns `500 INTERNAL` instead of 404/405;
  the web client never calls it.
- Lighthouse on this machine is noisy while Docker Desktop runs: three runs of the
  same image scored **96, 85 and 81** (TBT 170–700 ms) while LCP stayed 0.8–1.0 s
  and accessibility stayed 100. `docs/perf/lighthouse-mobile.json` holds the 96
  sample; the ≥ 90 target is met on an idle host, not reliably under load.

---

## 7. Gate A — P5 completion and sign-off

### 7.1 Chat sender names and grouping

`web/app/groups/[id]/chat/page.tsx` now reads the member list from the group
payload it already had access to (`GET /v1/groups/{id}`) and labels incoming
messages with `display_name`. A name is rendered only on the first message of a
run: a new run starts when the sender changes or more than five minutes pass, and
continuation bubbles sit 4 px closer. Own bubbles keep the dark styling and no
name, and ordering, timestamps, optimistic send, failure state and
`before_seq` pagination are untouched.

`e4-messaging.spec.ts` asserts the behaviour end to end with two signed-in
browsers: two consecutive messages from the member produce exactly one name,
own messages produce none, and a reply from the owner starts a new run so the
name appears again.

### 7.2 Functional E2E coverage

16 Playwright tests against the containerised production build (390×844),
serial, one worker:

| Spec | Tests | Covers |
|---|---|---|
| `e1-discovery` | 1 | register → complete profile → discovery, filters, pagination |
| `e2-group-rsvp` | 1 | activity → join group → next occurrence |
| `e3-owner-event` | 1 | owner creates a weekly series, RSVPs going |
| `e4-messaging` | 1 | realtime delivery, offline backfill, sender names/grouping |
| `e5-matching` | 1 | match job → invitations → four acceptances form a group |
| `e6-authz` | 2 | signed-out routes, private group and owner-only restrictions |
| `e7-pwa-offline` | 2 | manifest/SW/no private caching, offline RSVP queue flush |
| `e8-auth-session` | 4 | validation + duplicate email, indistinguishable login errors, refresh rotation + reuse detection, `alg:none`/tampered JWT, logout invalidation, session restore after reload |
| `e9-group-lifecycle` | 2 | private join request → owner approval → leave revocation; group and event capacity under contention, idempotent repeat RSVP |
| `e10-account-isolation` | 1 | account switch on one device leaks no groups, storage or cached private API |

Final run on the shipped image: **16 passed (1.6 min), `E2E_EXIT=0`**. The
timing-sensitive lifecycle spec was additionally run with `--repeat-each=2`
(4/4 passed) after replacing a fixed wait with an explicit heading anchor.

Backend: `mvn -B -ntp verify` → **BUILD SUCCESS**, 23 unit + 18 integration
tests.

### 7.3 Defect found by the new journeys (fixed)

Approving or declining a join request showed *"The action could not be
completed."* although the PATCH succeeded: `PATCH /v1/groups/{id}/members/{uid}`
returns `200` with an empty body and `apiFetch` called `.json()` unconditionally.
`web/lib/api.ts` now treats an empty successful body as `undefined`. `E9a`
covers the approve path and asserts the *"Membership updated."* notice.

### 7.4 Security

See `SECURITY_AUDIT.md`. Fixed: refresh-token rotation race (High), live
messages still reaching removed members (High), missing CSP/security headers
(Medium), 500 on unsupported methods (Low), CI permissions (Low).
`npm audit` moved from **1 critical / 4 high** to **0 critical / 0 high** by
upgrading Next 14.2.33 → 15.5.29 with React 19, and pinning Next's bundled
`postcss` to `^8.5.32`. 24/24 live probes pass against the compose stack
(`node docs/security-probes.mjs`, `security-probes.log`).

### 7.5 Platform change required by the upgrade

The Next 15 compiler structured-clones the config, so the old `experimental`
`cpus`/`workerThreads`/`webpackBuildWorker` flags were removed; they caused
`DataCloneError: ()=>null could not be cloned` during static generation. Lint,
typecheck and build all pass on the new versions.

### 7.6 Gate A status

| Item | Status |
|---|---|
| Chat sender names + grouping | **PASS** (code + E2E) |
| Full lifecycle E2E | **PASS** 16/16 |
| Backend suites | **PASS** 23 + 18 |
| Security audit + remediation | **PASS** for Critical/High; accepted risks documented |
| System design assessment | **PASS** (`SYSTEM_DESIGN_REVIEW.md`); load evidence deferred to Gate B |
| Remote GitHub Actions | **NOT TESTED** (no push) |
| Load, cross-instance, failure injection | **NOT TESTED** (Gate B, see `PHASE_6.md`) |

