# High-Level Design (HLD)
## Recurring-Activity Friendship Platform ("Kinship")

Version: 1.1 | Status: Reviewed for implementation (Phase 0) | Date: 2026-11-03

---

## 1. Purpose and Scope

Kinship is a mobile-first web application (PWA) that helps adults form local
friendships through **small, recurring activity groups**. The product optimises
for repeated in-person meetings, not online engagement.

**Business objective:** in one launch city, prove that groups formed on Kinship
keep meeting after 8 weeks (North Star: *Recurring Group Retention*).

**Core loop:** discover activity → join a group of 4–6 → RSVP to recurring
occurrences → message the group.

### 1.1 MVP scope

| In scope | Out of scope (post-MVP) |
|---|---|
| Email/password auth, JWT access + rotating refresh | Email verification/reset, OAuth, MFA |
| Activity discovery by geo, category, size, start time | Full-text search, recommendations, sponsored ranking |
| Group create/join/approve/leave, owner role | Group deletion, transfers beyond automatic promotion |
| Weekly/biweekly/monthly recurring series, 8-week horizon | Editing recurrence, exceptions, iCal import/export |
| RSVP with capacity enforcement | Waitlists, paid tickets, attendance check-in |
| Async matching + invitations with 48 h expiry | ML ranking, chat before group confirmation |
| Group chat (REST + WebSocket) | Attachments, reactions, read receipts, push |
| PWA shell, offline read of discovery | Native apps |
| Observability, load and failover baseline | Multi-region, Kubernetes, Kafka, sharding |
| — | Account deletion, data export, push/email notifications |

---

## 2. Architectural Principles

1. **Modular monolith first.** One Spring Boot deployable with hard package
   boundaries; services can be extracted later without changing the API.
2. **Read-heavy, geo-dominated.** Discovery is a geospatial query and must stay
   under 200 ms p95 on a cache miss, 50 ms on a hit.
3. **Separate geo workloads.** Static inventory lives in PostgreSQL/PostGIS;
   per-second presence is **not** part of the MVP and is deferred to Redis geosets.
4. **Recurring events are first-class.** A series is a row; occurrences are
   materialised 8 weeks ahead and generated idempotently.
5. **Async work never blocks a request.** Matching, generation, reminders and
   expiry run on schedulers backed by database state.
6. **Fail-open reads, fail-closed auth.** If Redis is down, discovery degrades to
   PostGIS; sessions and realtime report `503` rather than silently weakening security.
7. **Boring technology.** Every component must be operable by a 3–4 person team
   and runnable on one Windows workstation.

---

## 3. Logical Architecture (MVP)

~~~text
┌──────────────────────────────────────────────────────────────┐
│  Browser (Next.js 14 PWA, mobile-first)                      │
│  TanStack Query · service worker · WS client · outbox        │
└───────────────┬──────────────────────────────────────────────┘
                │ HTTPS (JSON) + WSS
┌───────────────▼──────────────────────────────────────────────┐
│  kinship-api — Spring Boot 3.3 (Java 21, single deployable)  │
│                                                              │
│  auth │ user │ activity │ group │ event │ rsvp │ match │ chat │
│  scheduler (@Scheduled) · OpenAPI (springdoc) · actuator      │
└───────┬───────────────────────────────────┬──────────────────┘
        │ JDBC (HikariCP)                    │ Lettuce
┌───────▼────────────────────┐   ┌───────────▼──────────────────┐
│ PostgreSQL 16 + PostGIS    │   │ Redis 7                      │
│ users, activities, groups, │   │ discovery cache, refresh     │
│ series, occurrences, rsvps,│   │ tokens, WS tickets, rate     │
│ messages, match jobs       │   │ limits, pub/sub fan-out      │
└────────────────────────────┘   └──────────────────────────────┘
~~~

Deployment for the MVP: one API instance, one web instance, managed PostgreSQL
and Redis. The design keeps all session/state outside the API process so a second
instance can be added without code changes (WS fan-out already uses Redis pub/sub).

---

## 4. Modules and Responsibilities

| Module (package `com.kinship.*`) | Responsibility | Depends on |
|---|---|---|
| `auth` | Registration, login, JWT issue/validate, refresh rotation, logout | user, cache |
| `user` | Profile, timezone, location/H3, interests | — |
| `activity` | Activity catalogue, discovery query, cache, cursor pagination | user |
| `group` | Group lifecycle, membership, owner rules, visibility | user, activity |
| `event` | Series creation, recurrence engine, occurrence generation, schedule reads | group |
| `rsvp` | Capacity-safe RSVP, counts, cancellation handling | event, group |
| `match` | Job queue, candidate search, scoring, group formation, invitations | user, group |
| `messaging` | Message persistence, per-group sequence, history, WS fan-out | group, cache |
| `notification` | Reminder/invitation logging stub (no delivery in MVP) | event, match |
| `common` | Errors, security, pagination, H3, time, tracing | — |

Rules: modules communicate through service interfaces, never by reaching into
another module's repository; no cyclic dependencies; each module owns its tables.

---

## 5. Geospatial Strategy

- `activities.location`, `groups.location`, `events.location`,
  `users.location` are `geography(Point,4326)` with GIST indexes.
- Discovery uses a **bounding-box pre-filter** (`location && ST_MakeEnvelope(...)`)
  followed by `ST_DWithin(location, :point, :radius_m)`, ordered by distance.
  This is one indexed query; no full scans, no offset pagination.
- `h3_cell_r7` is stored on `users` and `activities` and is used for:
  - the discovery **cache key** (origin cell), and
  - matching candidate scope (`k_ring(cell, 2)`).
  It is **not** an additional SQL pre-filter for discovery — the bounding box is
  cheaper and exact. The earlier "all geo queries use H3 pre-filter" rule is
  replaced by "all discovery queries use a bounding-box pre-filter before
  `ST_DWithin`; H3 is the cache and matching dimension".
- H3 cell values are computed on write (Java H3 library) and stored as text;
  no PostGIS H3 extension is required.
- Redis geosets for live presence are deferred until a presence feature exists.

---

## 6. Caching

| Layer | Content | TTL | Invalidation |
|---|---|---|---|
| Redis discovery | Ordered cards per `(cell, category, radius_bucket)`, max 200 | 120 s | TTL only (MVP) |
| Redis session | Refresh token hashes | 30 d | Rotation/logout |
| Redis realtime | WS tickets | 60 s | Single use |
| Redis rate limit | Fixed-window counters | window | TTL |
| HTTP | Discovery `ETag` (strong hash of response body) | request | `If-None-Match` → 304 |

Read path: `GET /v1/activities` → Redis lookup → on hit return (< 10 ms) → on
miss query PostGIS → write cache → return. Target hit rate > 85 % for a warm
single-city workload.

No write-through invalidation in the MVP: the maximum staleness is the 120 s TTL.
This is acceptable because activity/venue data changes on the order of hours and
was chosen over event-driven purge complexity.

---

## 7. Asynchronous Processing

| Job | Trigger | Idempotency |
|---|---|---|
| Occurrence generation (daily 02:15 UTC) | `@Scheduled` | `UNIQUE (series_id, start_time)` |
| Matching worker (every 10 s) | `@Scheduled`, claims with `SKIP LOCKED` | job status transition |
| Invitation expiry (every 10 min) | `@Scheduled` | status transition |
| Reminder scan (hourly) | `@Scheduled` | Redis key per `(event,user)` |

There is **no Kafka/SQS in the MVP**. The database is the queue: `match_jobs.status`
is the work state, and claiming is a single atomic `UPDATE ... RETURNING`.
A dedicated broker is added only if job throughput or fan-out exceeds what the
database can serve (post-MVP).

---

## 8. Database

- PostgreSQL 16 with PostGIS 3.4; one schema, Flyway migrations.
- Access via Spring JDBC (`JdbcClient`); no JPA entity graph, so the
  batched/lateral queries in `lld.md` remain explicit.
- Keyset pagination everywhere; no `OFFSET` on hot paths.
- JSONB only for genuinely flexible data: `recurrence_rule`, `preferences`, `availability`.
- Partitioning, materialised views, read replicas and PgBouncer are **deferred**:
  the MVP dataset (≈10k users, ≈1k groups, ≈50k occurrences, ≤5M messages/year)
  does not justify them, and partitioning would force `start_time` into the
  `events` primary key. Trigger for revisiting: > 20M message rows or
  discovery p95 > 300 ms with a warm cache.

---

## 9. Scaling Path (post-MVP, not built now)

| Stage | Trigger | Action |
|---|---|---|
| 2 API instances | CPU > 60 % sustained | Stateless API + Redis pub/sub already supports it; sticky sessions not required |
| Read replicas | Discovery cache miss p95 > 300 ms | Route discovery reads to a replica |
| Job workers | Matching backlog > 10 min | Run the worker in a separate deployment mode |
| Broker | > 100 jobs/s or cross-service events | Introduce Kafka/SQS |
| Geo partitioning | Multi-city | Partition by city/H3 prefix; keep cache keys city-scoped |
| Container platform | > 5 services or multi-region | Kubernetes, HPA, KEDA |

These are documented so the MVP does not pretend to have them.

---

## 10. Non-Functional Targets

| Metric | Target | How measured |
|---|---|---|
| Discovery p95, cache hit | < 50 ms | Micrometer histogram + k6 |
| Discovery p95, cache miss | < 300 ms | same |
| Group/event detail p95 | < 300 ms | same |
| WS delivery p95 (same pod and cross-pod) | < 150 ms | k6 WS scenario |
| Discovery cache hit rate | > 85 % warm | Micrometer counter |
| Matching batch, one city | < 5 min | job duration metric |
| API availability | 99.5 % (MVP) | uptime check |
| Error rate under Redis outage | < 1 % for discovery | chaos test |

---

## 11. Security Overview

- **Authentication:** BCrypt(12) passwords, 15-minute HS256 access JWT, opaque
  rotating refresh token in an httpOnly `SameSite=Lax` cookie. Refresh reuse
  revokes the family.
- **Authorization:** server-side membership checks on every group-scoped
  operation (matrix in `lld.md` §2); non-visible resources return 404.
- **WebSocket:** 60-second single-use ticket; the access token never appears in a
  URL or log.
- **Input safety:** Bean Validation on all request bodies; JDBC parameter binding
  only; message body length capped at 2000 chars.
- **Rate limiting:** Redis counters on auth, discovery, RSVP, messaging and matching.
- **Secrets:** environment variables only (`KINSHIP_JWT_SECRET`, DB and Redis
  credentials); no secrets in the repository or in `application.yml`.
- **Logging:** JSON logs with `request_id`; emails, tokens and message bodies are
  never logged. Passwords are never serialised.
- **Transport:** HTTPS/WSS in every non-local environment; `Secure` cookie flag.

Threats explicitly accepted for the MVP: no email verification (spam signups are
rate-limited), no user blocking/reporting (owners can remove members), and no
content moderation beyond length limits.

---

## 12. Decisions, Assumptions and Open Items

**Confirmed for implementation**

1. Backend is **Java 21 + Spring Boot 3.3.x + Maven** (replaces the earlier
   "Node or Python" placeholder). The repository had no implementation, so this
   resolves the stack without migration cost.
2. PostgreSQL 16 + PostGIS, Redis 7, Next.js 14 (App Router) + TypeScript.
3. Single deployable modular monolith; one launch city; six delivery phases.
4. No Kafka, Kubernetes, presence, push/email, payments, or moderation in the MVP.

**Assumptions to validate during Phase 1**

1. The installed Node.js (v26) can build the pinned Next.js 14 app; if not, pin a
   supported Node LTS via `.nvmrc` and record the change.
2. Redis is available locally for sessions and cache; there is no in-memory
   fallback for auth.
3. A single API instance is sufficient for the pilot; the code still externalises
   state so a second instance works.
4. Discovery staleness up to 120 s is acceptable to users and organizers.

**Open items requiring owner input** (tracked in `implementation_plan.md` §8)

1. Authorise installation of Docker Desktop + WSL2, or native PostGIS + Redis, on
   the development machine (currently neither PostGIS nor Redis nor Docker is present).
2. Confirm the Java/Spring stack decision above (default: proceed as documented).
3. Confirm the Node/Next.js version pairing once a build is attempted.

---

## 13. Key Trade-offs

| Decision | Rationale | Cost accepted |
|---|---|---|
| Modular monolith, not microservices | 8-week MVP, 3–4 people, one deployable | Extraction later; package discipline enforced by review |
| PostGIS for inventory, no presence service | Static data, simple ops | No "who is nearby now" feature in MVP |
| DB-backed jobs instead of Kafka | Zero extra infrastructure, transactional claiming | Job throughput bounded by DB |
| TTL-only discovery cache | No invalidation pipeline to build | Up to 120 s stale cards |
| Async matching | CPU-heavy and not user-blocking | Users wait for a job result |
| Capacity via row lock | Correct under concurrency with simple SQL | Hot-row contention on very popular events (acceptable at MVP scale) |
