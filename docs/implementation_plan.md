# Implementation Plan
## Recurring-Activity Friendship Platform ("Kinship")

Version: 1.1 | Status: Reviewed for implementation | Timeline: 8 weeks to MVP

---

## 1. Objective

Ship a production-grade MVP in **one city** that proves the core loop:
**discover activity → join small group → attend recurring events → message the
group**. Retention (groups still meeting after 8 weeks) is the North Star; every
phase exits only when its acceptance checks pass.

Scope boundaries are defined in `hld.md` §1.1 and are not re-negotiated per phase.

---

## 2. Phase Map and Dependencies

~~~text
P0 Documentation baseline (done)
        │
P1 Foundation ──────────────► P2 Groups & Recurring Events
   │                              │            │
   │                              │            └──────► P4 Messaging
   └──────────► P3 Matching ◄─────┘
                     │
P5 Web app, PWA & E2E ◄── P1–P4 complete
                     │
P6 Observability, Load & Hardening
~~~

| Phase | Weeks | Depends on | Primary deliverable |
|---|---|---|---|
| **P0 — Documentation baseline** | done | — | Reviewed HLD/LLD/ERD/plans/prompts consistent with the Java stack |
| **P1 — Foundation** | 1–2 | P0, environment prerequisites | Auth + schema + discovery API + minimal web shell + CI |
| **P2 — Groups & Recurring Events** | 3–4 | P1 | Group lifecycle, recurrence engine, RSVP with capacity |
| **P3 — Matching** | 5 | P1 (users/interests), P2 (group creation) | Async job, scoring, invitations, group confirmation |
| **P4 — Messaging** | 6 | P2 (groups/membership) | REST history + WebSocket fan-out |
| **P5 — Web App, PWA & E2E** | 7 | P1–P4 | Complete UI flows, offline shell, performance budget, E2E suite |
| **P6 — Observability, Load & Hardening** | 8 | all | Metrics/alerts, k6 baseline, failover, security pass |

Each phase is a vertical slice: backend endpoint(s) + the UI that uses them +
tests + a short `PHASE_N.md`. Do not start a phase before the previous phase's
Definition of Done is met.

---

## 3. Repository Layout (target)

~~~text
kinship/
├─ api/                     Spring Boot 3.3, Java 21, Maven
│  ├─ pom.xml
│  └─ src/main/java/com/kinship/{auth,user,activity,group,event,rsvp,match,messaging,notification,common}
│  ├─ src/main/resources/{application.yml,db/migration/V1__schema.sql,V2__indexes.sql}
│  └─ src/test/java/...     JUnit 5, Spring Boot Test, Testcontainers
├─ web/                     Next.js 14 App Router, TypeScript, Tailwind, TanStack Query
├─ infra/                   docker-compose.yml, postgres init, .env.example
└─ docs/                    this documentation set
~~~

One Maven module for the MVP. Multi-module split is a post-MVP refactor; package
boundaries provide the same discipline at this scale.

---

## 4. Phase Detail

### P0 — Documentation Baseline (complete)

- Harmonised stack, schema, API contracts, phase prompts and test strategy.
- Corrected ERD/LLD inconsistencies (series vs occurrences, message sequence,
  RSVP capacity, missing auth/membership/match-invitation endpoints).
- Open environment gaps recorded (§7).

### P1 — Foundation (Weeks 1–2)

**Backend**

1. Maven project: Spring Boot 3.3.x, Java 21; starters: web, validation, security,
   jdbc, data-redis, actuator; Flyway (+ `flyway-database-postgresql`); springdoc;
   Nimbus JOSE; H3 Java library; PostgreSQL driver.
2. `V1__schema.sql` implementing `erd.md`; `V2__indexes.sql` from `indexes.sql`.
3. `auth` module: register, login, refresh rotation, logout; BCrypt 12; JWT filter.
4. `user` module: `GET/PATCH /v1/users/me`, interests, H3 recomputation.
5. `activity` module: `GET /v1/activities` with bbox + `ST_DWithin`, Redis cache
   (120 s), cursor pagination, batched next-event/member-count hydration.
6. Seed loader (Flyway repeatable or `CommandLineRunner` guarded by profile):
   3 categories, 50 activities, 10 groups, 5 recurring series in one demo city.
7. OpenAPI published at `/v3/api-docs`; `/healthz` and `/readyz`.
8. Error envelope + request-id filter + JSON logging + PII redaction.

**Frontend**

- Next.js shell, Tailwind, TanStack Query provider, auth screens (register/login),
  discovery list with skeletons and cursor "load more".

**Infra / CI**

- `infra/docker-compose.yml` (api, web, postgis/postgis:16-3.4, redis:7) and
  `.env.example`; native-run instructions in the README for machines without Docker.
- GitHub Actions: backend `mvn verify`, frontend lint/typecheck/build.

**Exit criteria (all must pass)**

- `mvn -q verify` green (unit + Testcontainers integration).
- `npm ci && npm run lint && npm run build` green.
- Register → login → refresh → logout works against a real DB and Redis.
- Seeded data: `SELECT count(*) FROM activities` = 50, groups = 10, event_series = 5.
- Discovery returns correct distances for known coordinates; a second identical
  request is served from cache (`X-Cache: HIT`).
- k6 smoke (5 VUs, 2 min): error rate 0 %, p95 < 300 ms cold, < 50 ms warm.
- `PHASE_1.md` records what was built, deviations, and known gaps.

### P2 — Groups & Recurring Events (Weeks 3–4)

**Backend**

1. Group create/detail/update, `GET /v1/groups?mine=true`, join/leave, member
   approval, owner promotion rules.
2. Recurrence parser + occurrence generator (8-week horizon), daily extension job.
3. Series create/cancel endpoints; schedule reads.
4. RSVP transaction with `FOR UPDATE` capacity check and `confirmed_count`.
5. Reminder scan stub (log only) with idempotency keys.
6. All list reads use keyset/cursor semantics and exclude cancelled occurrences.

**Frontend**

- Group detail (members, next events), create group, join/leave, RSVP buttons with
  optimistic update and rollback, event creation form (weekly/biweekly/monthly).

**Exit criteria**

- Occurrence generator run twice → no duplicates (integration test).
- RSVP race: event capacity 10 with 9 confirmed, 10 concurrent GOING → exactly 1
  success, 9 × `409 EVENT_FULL`, `confirmed_count = 10`.
- Switching GOING → DECLINED → GOING restores the count exactly.
- Feb 29 monthly series and both DST transitions covered by unit tests.
- Owner leaving with one remaining ACTIVE member promotes that member; owner
  leaving the last member archives the group.
- `PHASE_2.md` written.

### P3 — Matching (Week 5)

**Backend**

1. `POST /v1/match-requests` with profile completeness check and single active job.
2. Worker claim with `SKIP LOCKED`, candidate cap 500, scoring and greedy formation
   exactly as `lld.md` §5.
3. Invitations, accept/decline, quorum group creation, 48 h expiry job, re-match.
4. Job status/result endpoint; `NO_CANDIDATES` and `JOB_TIMEOUT` paths.

**Frontend**

- Profile form (location, availability, interests), match request (distance, size),
  job progress, invitation list (polled on focus, no push), accept/decline,
  resulting group navigation.

**Exit criteria**

- A seeded user with ≥ 2 shared interests receives a proposal of 4 members.
- Users who shared 2+ confirmed groups in 90 days are never proposed together.
- Decline below quorum expires the proposal and queues exactly one replacement job.
- One-city batch with 5,000 seeded candidates completes in < 5 min.
- Duplicate `POST /match-requests` returns the existing job id.
- `PHASE_3.md` written.

### P4 — Messaging (Week 6)

**Backend**

1. Per-group sequence allocation in the message insert transaction; publish after commit.
2. `POST /realtime/tickets`, WebSocket handler with ticket auth, membership check.
3. Redis pub/sub fan-out; history endpoint with `before_seq`.
4. Outbox dedupe by `client_msg_id`; close codes and heartbeat.
5. Optional single-instance fallback is **not** built; pub/sub covers it.

**Frontend**

- Chat screen: virtualised list, send with optimistic bubble, ack/rollback,
  reconnect with backoff, REST backfill, offline banner.

**Exit criteria**

- Two app instances (shared DB/Redis) deliver a message across instances in p95 < 150 ms.
- Duplicate `client_msg_id` creates one row and returns the original sequence id.
- Kill/restart Redis → sends fail with a clear error, no duplicate or out-of-order
  rows after Redis returns; sequence continues from the DB counter.
- Reconnect after a forced socket close backfills missed messages exactly once.
- `PHASE_4.md` written.

### P5 — Web App, PWA & E2E (Week 7)

**Frontend**

1. Complete the five core flows from `testing_plan.md` §4 with accessible markup.
2. Route-level code splitting; lazy map/chat; image proxy + srcset.
3. Service worker: app shell precache, discovery stale-while-revalidate (5 min),
   group detail network-first (30 s), offline banner and read-only cache.
4. Skeleton screens, optimistic RSVP/message, ETag/304 on discovery.
5. Lighthouse mobile run recorded in `PHASE_5.md`.

**Testing**

- Playwright suite for all five flows against a composed stack; CI job on pull requests.

**Exit criteria**

- Playwright suite green on mobile viewport (390 × 844).
- Lighthouse mobile performance ≥ 90, LCP < 2.5 s on throttled 4G.
- Discovery response includes `ETag`; repeat request returns 304.
- Offline: discovery list renders from cache with banner; actions queue and flush.
- `PHASE_5.md` written.

### P6 — Observability, Load & Hardening (Week 8)

**Backend / Ops**

1. Micrometer metrics: discovery latency + cache hit ratio, RSVP conflicts, job
   duration/backlog, WS connections, message rate; Prometheus endpoint.
2. Alerts: discovery p95 > 300 ms 10 min, cache hit < 70 % 15 min, job backlog > 10,
   DB pool exhaustion, Redis down.
3. k6 scripts (discovery, RSVP race, messaging) with baseline in `docs/perf/baseline.md`.
4. Failover test: stop Redis → discovery still 200 via PostGIS, error rate < 1 %.
5. Security pass: auth matrix checks, rate-limit checks, PII scan of logs, dependency
   vulnerability scan (`mvn dependency-check` or OWASP plugin).
6. Data consistency job: nightly `confirmed_count` reconciliation + log drift.

**Exit criteria**

- 2-hour soak (300 VUs): p95 stable, heap growth < 5 %, no 5xx.
- Redis-down discovery error rate < 1 %, recovery < 60 s after restart.
- All P1–P6 acceptance checks reproducible from a clean checkout per the README.
- Final `PHASE_6.md` includes the baseline numbers and open risks.

---

## 5. Definition of Done (every phase)

1. Code merged with tests; `mvn -q verify` and `npm run build` green.
2. New/changed endpoints reflected in the OpenAPI output and in `lld.md` if the
   contract changed (docs and code in the same change).
3. No new P1/P2 defects; no secrets, credentials or generated artefacts committed.
4. Integration test added for every new transactional invariant.
5. `PHASE_N.md` written: built, deferred, decisions, risks, follow-ups.
6. README run instructions still work on a clean checkout.

---

## 6. Environment Prerequisites

Verified on 2026-11-04 (Windows 11):

| Component | Status | Detail |
|---|---|---|
| Java 21 | ✅ | Temurin 21.0.13; `mvn -version` resolves it |
| Maven | ✅ | 3.9.9 |
| Node.js / npm | ✅ | v26.12.1 / 11.20.0; Next.js 14 build verified |
| Git | ✅ | 2.56.0 (repository not yet initialised) |
| PostgreSQL 16 | ✅ | Native service on 5432; Compose PostGIS on host 5433 |
| PostGIS | ✅ | 3.4.3 in `postgis/postgis:16-3.4` |
| Redis 7 | ✅ | `redis:7-alpine`, host 6379 |
| Docker Desktop | ✅ | 29.9.2 + WSL2; Testcontainers pinned to 1.21.4 for Docker API 1.56 |

P1 passed against a fresh `kinship_acceptance` database. The Compose host port is
5433 so the native PostgreSQL service on 5432 remains untouched.

---

## 7. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| PostGIS/Redis absent on dev machine | P1 blocked | Owner installs (see §6); do not code around missing infrastructure |
| Next.js 14 vs Node 26 incompatibility | P1 frontend blocked | Verify `npm ci && npm run build`; pin Node LTS via `.nvmrc` |
| Cache staleness (120 s) surprises testers | Flaky acceptance | Document expected staleness; tests flush the specific key |
| RSVP hot-row contention | 500 ms tail latency on popular events | Accept for MVP; measure in P6; revisit only if p95 > 500 ms |
| Recurrence DST/month-end bugs | Wrong occurrence times | Unit tests for both DST transitions and Feb 29 before P2 exit |
| Message cross-instance delivery | Duplicate/lost messages | Publish after commit, dedupe by `message_id`, two-instance test |
| Matching combinatorial blow-up | Job timeout | Candidate cap 500, early pruning, 5-minute job timeout |
| Scope creep (push, payments, moderation) | Missed 8-week target | Explicit out-of-scope list in `hld.md` §1.1 |

---

## 8. Open Items Requiring Owner Input

| # | Item | Default if no answer |
|---|---|---|
| 1 | Confirm Java 21 + Spring Boot backend (replaces Node/Python) | Proceed as documented |
| 2 | ~~Authorise installing PostGIS and Redis (or Docker Desktop + WSL2)~~ | **Resolved 2026-11-04**: Docker Desktop + WSL2; PostGIS 3.4.3 and Redis 7 running |
| 3 | ~~Confirm Node/Next version strategy if Next 14 fails on Node 26~~ | **Resolved**: Next.js 14 build verified on the installed Node 26 |
| 4 | Confirm launch city/timezone for seed data | Use the developer's local city in `Europe/Berlin`-style seed |

---

## 9. Team and Post-MVP Roadmap

Suggested team: 1 backend engineer (API, DB, matching), 1 fullstack engineer
(frontend, WS, PWA), part-time DevOps, part-time PM/designer. A solo AI agent can
execute the phases sequentially using `delivery_phase_prompt.md`.

| Month | Theme | Outcome |
|---|---|---|
| 3 | Close the loop in one city | 100 groups, 1,000 members, retention measured |
| 4 | Local partnerships | Venue deals, sponsored activities |
| 5 | Monetisation v1 | Match Plus subscription, event fees |
| 6 | City #2 | Same architecture, city-scoped seed and cache keys |
| 7–9 | Optimise | Matching quality, retention, unit economics |

**North Star:** Recurring Group Retention (% of groups still meeting after 8 weeks).
Supporting: activity → join conversion, RSVP rate, match acceptance, 30/60/90-day
retention, discovery cache hit rate.
