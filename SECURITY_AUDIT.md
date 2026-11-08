# Kinship — Security Audit (P5 sign-off)

Assessment date: 2026-11-06. Target: the local Kinship stack
(`docker compose -f infra/docker-compose.yml`). No external host, no production
data and no third-party service was touched. This is a first-party assessment by
the implementation team, **not** an independent penetration test and not a
certification.

## 1. Scope and method

Reviewed and exercised:

| Surface | How |
|---|---|
| Auth API (`/v1/auth/*`), JWT, refresh rotation | Code review + `AuthFlowIT` + probes + E8 |
| Authorization on groups, events, messages, matching | Code review (`requireVisible`, `requireMember`, `requireOwner`) + probes + E6/E9 |
| WebSocket handshake, tickets, fan-out | Code review + `MessagingIT` + `GroupSessionRegistryTest` + E4 |
| Message persistence and idempotency | `MessagingIT`, probes, E4 |
| Frontend token storage, service worker, outbox | Code review + E7/E10 + `web/public/sw.js` |
| Docker, Compose, CI | Code review of `api/Dockerfile`, `web/Dockerfile`, `docker-compose.yml`, `ci.yml` |
| Dependencies | `npm audit` (web); Maven versions pinned in `pom.xml` |
| Error handling and information exposure | Code review + probes |

Reference frameworks: OWASP ASVS 4.0 (V2 authentication, V3 session, V4 access
control, V5 validation, V8 data protection, V9 communication, V14 configuration)
and OWASP API Security Top 10 (2023). Only the items that produced a finding or a
positive verification are listed; this is not a clause-by-clause certification.

Automated scanner results: `npm audit` only. **No Java CVE database scan was run**
(no OWASP dependency-check DB available offline), no DAST scanner, no fuzzing and
no WebSocket flood test. Those are listed as NOT TESTED, not as "clean".

## 2. Findings

### Fixed during this sign-off

#### H1 — Refresh token could be rotated twice (High, fixed)
**Where.** `RefreshTokenService.doRotate` read the stored hash and deleted the key
in two separate Redis calls.
**Impact.** Two concurrent requests with the same refresh cookie could both pass
the hash check and both receive valid tokens, defeating single-use rotation and
allowing a stolen token to be redeemed alongside the legitimate client.
**Evidence.** Code path: `GET` → compare → `DELETE`; reproduced conceptually by
the new concurrency test, which failed to guarantee one winner before the fix.
**Fix.** `GETDEL` makes consume-and-compare atomic; a hash mismatch now revokes
the whole token family as well.
**Regression test.** `AuthFlowIT.concurrentRefreshOfTheSameTokenHasExactlyOneWinner`
(4 parallel refreshes → exactly one 200).

#### H2 — Removed members kept receiving live messages (High, fixed)
**Where.** `GroupSessionRegistry.broadcast` sent every frame to every socket in
the group; membership was only checked when the ticket was issued.
**Impact.** A member removed by the owner, or a user who left, continued to read
new messages on the already-open socket until it reconnected.
**Fix.** `RedisGroupFanout` re-reads the group's active member ids for each frame
and `GroupSessionRegistry.broadcast` closes sockets whose user is not in that set.
**Regression test.** `GroupSessionRegistryTest.removedMemberSocketIsClosedAndNeverReceivesTheFrame`;
`e9-group-lifecycle.spec.ts` verifies API-level revocation after leave.

#### M1 — No browser security headers (Medium, fixed)
**Where.** `next.config.mjs` set only cache headers.
**Evidence.** Probe `web-security-headers`: `content-security-policy=missing
x-content-type-options=missing x-frame-options=missing`.
**Impact.** No defence-in-depth against injected script execution, MIME sniffing
or clickjacking.
**Fix.** CSP (self + API/WS origins, `object-src 'none'`, `frame-ancestors
'none'`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy`, `Permissions-Policy`. `script-src` keeps `'unsafe-inline'`
because Next injects inline bootstrap scripts; a nonce-based CSP needs middleware
and is deferred.

#### L1 — Unsupported methods returned 500 (Low, fixed)
`GET /v1/match-requests` (POST-only) fell through to the generic handler and
returned `INTERNAL`. Now `405 METHOD_NOT_ALLOWED` via
`GlobalExceptionHandler.handleMethodNotAllowed`.

#### Dependency advisories (Critical → none, fixed)
`npm audit` before: 1 critical, 4 high — `next` critical RCE advisories
(GHSA-p293-qw3h-jr36, GHSA-2xp9-vwfh-vxw4, GHSA-2464-fhr4-w9xw) plus a high
`postcss` path-traversal advisory. No 14.x release is patched (latest 14.2.35 is
still in range).
**Fix.** Next 14.2.33 → 15.5.29 with React 19 and React Query 5.106; the obsolete
single-worker `experimental` flags were removed (they broke the Next 15 build with
`DataCloneError`). Next's bundled `postcss@8.4.31` is pinned to `^8.5.32` via
`overrides`.
**After:** `npm audit` → 0 critical, 0 high, 6 moderate (dev-only ESLint chain).
**Retested.** typecheck, lint, production build and the full Playwright suite run
on the upgraded stack.

#### CI least privilege (Low, fixed)
`.github/workflows/ci.yml` now declares `permissions: contents: read`.

### Accepted risks (documented, not fixed)

| ID | Risk | Why accepted | Mitigation in place |
|---|---|---|---|
| A1 | Access JWT stays valid for its 15 min TTL after logout or refresh-token reuse | Stateless verification is a deliberate design (no session read per request); revoking would need a Redis deny-list on every request | Short TTL, HTTPS-only deployment, refresh cookie httpOnly + SameSite=Lax; asserted and documented in `E8b` |
| A2 | A concurrent refresh from two tabs logs the other tab out (family revocation) | Strict reuse detection is the chosen security posture; a grace window would weaken it | Only one refresh runs per page load; users can sign in again |
| A3 | Rate limiter fails open when Redis is unavailable | Availability preference documented in the LLD; enforcement must not take the API down | Per-IP/per-account windows when Redis is healthy; probe shows 429 + `Retry-After` |
| A4 | Redis has no persistence (`--save "" --appendonly no`) | It is a dev/demo compose file; sessions are disposable | Users re-authenticate; Postgres keeps the data |
| A5 | PostgreSQL and Redis publish ports 5433/6379 on the host with demo credentials | Local development convenience | Not for production; the audit recommends removing the mappings in any shared environment |
| A6 | No TLS in compose; `KINSHIP_COOKIE_SECURE` defaults to false | Local HTTP development | Flag exists and must be `true` behind TLS; HSTS belongs at the terminator |
| A7 | `POST /v1/auth/register` returns `EMAIL_TAKEN` (409) | Needed for the sign-up UX; login already returns an identical error for unknown email and wrong password | Rate-limited per IP+email; no account details in the response |
| A8 | No per-user WebSocket concurrency limit | Not exercised yet; single-instance deployment | Heartbeat, single-use tickets, membership re-check per frame; Gate B will test floods |

### Informational / verified-absent

- SQL injection: all queries are `JdbcClient` named parameters; array inputs are
  bound via `string_to_array(:ids, ',')`, never concatenated into SQL text.
- Mass assignment: request DTOs are records with explicit fields; a PATCH sending
  `id`, `role`, `email` is ignored (probe `mass-assignment`).
- BOLA/IDOR: private groups return 404 to non-members; history/RSVP require active
  membership (probes `BOLA-*`); jobs and invitations are owner-scoped (404).
- JWT algorithm confusion: `alg: none` and tampered signatures return 401
  (probes, `E8c`); the verifier is a MAC verifier, so asymmetric substitution is
  rejected.
- Password storage: BCrypt strength 12; refresh secrets stored as SHA-256 hashes.
- Refresh cookie: `HttpOnly`, `SameSite=Lax`, `Path=/v1/auth`, 30-day max-age.
- CORS: allow-list from configuration; unknown origin gets 403 with no
  `Access-Control-Allow-Origin`; credentialed requests allowed only for the app
  origin.
- Private response caching: API sends `Cache-Control: no-cache, no-store`; the
  service worker never caches `/v1/auth`, `/v1/users`, `/v1/match`, `/v1/realtime`
  or `*/messages`; `PURGE_DATA` runs on login, register and logout.
- Browser storage: no access token, refresh token or profile in
  localStorage/sessionStorage (`E7a`, `E10`).
- Error handling: generic `INTERNAL` with a request id; no stack traces, no SQL,
  no dependency details in responses.
- Actuator: only `health` and `info` exposed; `/actuator/env` is 404 even
  authenticated.
- Docker: API and web run as non-root users; only the four services are exposed;
  no secrets baked into images; `.env` is gitignored.
- Offline replay: queued actions are replayed with a freshly refreshed access
  token, so a revoked membership is rejected at flush time.

## 3. Probes actually executed

`node docs/security-probes.mjs` against the compose stack (throwaway accounts
only). Each line is a live HTTP assertion, not a static inspection:

```
PASS BOLA-private-group     404 NOT_FOUND
PASS BOLA-history           403
PASS private-join-pending   200 PENDING
PASS pending-cannot-read    403
PASS BOLA-rsvp              403
PASS BOLA-event-detail      403
PASS BOLA-match-job         404
PASS BOLA-invitation        404
PASS jwt-alg-none           401
PASS jwt-garbage            401
PASS unauthenticated        401 UNAUTHENTICATED
PASS login-rate-limit       429 retry-after=55
PASS method-not-allowed     405 METHOD_NOT_ALLOWED
PASS pagination-cap         400 VALIDATION_ERROR
PASS mass-assignment        200 id_unchanged=true email=true
PASS message-length         400 VALIDATION_ERROR
PASS cors-evil-origin       403 acao=null
PASS cors-allowed-origin    acao=http://localhost:3000
PASS refresh-cookie         kinship_rt=<redacted>; Path=/v1/auth; HttpOnly; SameSite=Lax
PASS private-cache-headers  cache-control=no-cache, no-store, ...
PASS web-security-headers   CSP, nosniff, X-Frame-Options, Referrer-Policy, Permissions-Policy
PASS health-public          200/200
PASS actuator-env           404
PASS duplicate-message      201/200 same=true

24/24 probes passed (PROBE_EXIT=0) on the final image.
```

## 3a. Gate B addendum (P6)

Gate A was approved and the production-readiness pass ran against the same local
stack. Security-relevant changes:

- `/actuator/prometheus` and `/actuator/metrics` are reachable without a JWT only
  because `MetricsAccessFilter` answers **404** unless `X-Metrics-Token` matches
  `KINSHIP_METRICS_TOKEN`. With no token configured both paths stay 404.
  Measured live: no header → 404, wrong header → 404, correct header → 200 with
  metrics; `/actuator/env` remains 404.
- Redis command and connect timeouts were cut from 2 s to 300 ms so a Redis
  outage fails fast. With Redis stopped, discovery still answered 200 in 1.0 s
  (7.2 s before the change), login answered 503 instead of hanging (curl showed
  no response at all before), and `/readyz` returned 503.
- The JDBC pool moved from 10 to 20 connections after the stress run. It is not
  externally reachable and changes no authorization rule.
- Realtime cross-instance delivery was exercised with two API instances sharing
  Redis: 150/150 frames delivered, p95 45 ms, 25/25 reconnects backfilled.
- The RSVP capacity race (19 members, 2 seats) produced exactly 2 successes,
  17 `EVENT_FULL` conflicts and 0 unexpected responses.

No Critical or High finding from Gate A was reopened. The removed-member socket
close, single-use tickets and per-message membership checks still hold.

## 4. Not tested

- Independent penetration test or red-team exercise: **not performed**.
- Java dependency CVE scan: **NOT COMPLETED.** Trivy downloaded its vulnerability
  database but found no analysable language files in the Spring Boot fat jar or
  its 92 unpacked `BOOT-INF/lib` jars on this Docker Desktop/WSL setup
  (`perf/results/trivy-*.log`). No OWASP dependency-check or Snyk run was possible.
  `npm audit` on the web app reports 0 critical and 0 high.
- Dependency review of the Maven tree by hand: versions are pinned in `pom.xml`
  (Spring Boot 3.3.13, Nimbus 9.37.3, H3 4.1.1, logstash-encoder 8.0); no scanner
  confirmed or denied advisories.
- WebSocket **flood** and slow-consumer behaviour: **NOT TESTED** — no per-user
  socket cap or bounded send queue exists. Ordinary concurrency and cross-instance
  fan-out were tested in Gate B: 50 sockets, 150/150 frames, p95 46 ms local and
  45 ms across two instances.
- TLS configuration, HSTS and real proxy behaviour: **NOT TESTED** (no TLS
  terminator in the local stack).
- Backup/restore: **rehearsed, PASS** — `pg_dump` 520,488 bytes, restore into a
  scratch database in 60 s with identical row counts. Still no scheduled backup,
  off-host copy or PITR (see `SYSTEM_DESIGN_REVIEW.md` L8).

## 5. Verdict

No open Critical or High finding remains after H1, H2 and the dependency upgrade.
Medium findings are either fixed (M1) or documented as accepted risks with a
deployment condition (A1–A8). This supports P5 sign-off for the local,
single-instance, HTTP development deployment only; it is not evidence of
production security for a multi-instance or internet-facing deployment.
