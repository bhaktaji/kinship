# Delivery Phases — Executable Prompt for a Coding Agent

Version 1.1 | Stack: Java 21 · Spring Boot 3.3 · PostgreSQL 16 + PostGIS · Redis 7 · Next.js 14

Use this file **one phase at a time**. Read `hld.md`, `erd.md`, `lld.md`,
`implementation_plan.md` and `testing_plan.md` before writing code. Do not start
a phase before the previous phase's Definition of Done passes.

---

## Global constraints (apply to every phase)

1. **Never block discovery on matching** or any async work.
2. **Discovery always uses a bounding-box pre-filter before `ST_DWithin`**; no
   unbounded geo scans. H3 is for cache keys and matching scope only.
3. **Cursor/keyset pagination only** for list endpoints; no `OFFSET` on hot paths.
4. **No N+1 queries**: hydrate next event, member count and group name in one
   batched or lateral query.
5. **Transactions own invariants**: capacity, sequence allocation, occurrence
   uniqueness. Use the SQL patterns in `lld.md` §3.5 and §6 — do not "improve"
   them without updating the doc.
6. **UTC in the database**, IANA zone on the series, local rendering on the client.
7. **Schema changes only via Flyway** (`V{n}__description.sql`); never edit an
   applied migration; never use `ddl-auto`.
8. **No secrets in code or YAML**; read `KINSHIP_JWT_SECRET`, DB and Redis
   credentials from the environment.
9. **No PII in logs**: no emails, tokens, message bodies, or coordinates at info level.
10. **Docs and code change together**: if a contract changes, update `lld.md` in
    the same change and note it in `PHASE_N.md`.
11. Stay inside the phase scope. Deferred items go to `PHASE_N.md`, not into code.

---

## Phase 1 — Foundation (2 weeks)

**Goal:** a running, tested slice from login to cached discovery, plus the schema.

1. Create `api/pom.xml`: Java 21, Spring Boot 3.3.x parent; starters web,
   validation, security, jdbc, data-redis, actuator; Flyway + PostgreSQL driver;
   springdoc-openapi; Nimbus JOSE; H3 Java; Lombok only if the team accepts it.
2. Implement `V1__schema.sql` from `erd.md` and `V2__indexes.sql` from
   `indexes.sql` (add the `postgis` extension in V1).
3. `auth`: register/login/refresh/logout per `lld.md` §1; BCrypt 12; JWT filter;
   refresh tokens hashed in Redis with rotation and reuse detection.
4. `user`: `GET/PATCH /v1/users/me`, `GET /v1/interests`, H3 recomputation.
5. `activity`: `GET /v1/activities` and `/{id}` per `lld.md` §3.2, Redis cache
   with `X-Cache`, cursor token, batched hydration.
6. Seed demo city: 3 categories, 50 activities, 10 groups, 5 event series.
7. Infra: `infra/docker-compose.yml`, `.env.example`, README run instructions.
8. CI workflow for backend and frontend.
9. Web: shell, auth screens, discovery list with skeletons and load-more.

**Acceptance**

~~~bash
cd api && mvn -q verify
cd web && npm ci && npm run lint && npm run build
~~~

- Cache: second identical request returns `X-Cache: HIT`.
- Seed counts match `implementation_plan.md` §6 (50 activities, 10 groups, 5 series).
- k6 smoke (5 VUs, 2 min) green; numbers recorded in `PHASE_1.md`.

---

## Phase 2 — Groups & Recurring Events (2 weeks)

**Goal:** the group/event/RSVP core loop with correct concurrency.

1. Groups: create/detail/update, join/leave, owner approval, promotion/archival.
2. Recurrence engine and 8-week generator per `lld.md` §4; daily extension job.
3. `POST /v1/groups/{id}/events`, `GET /v1/groups/{id}/events`,
   `GET /v1/events/{id}`, `PATCH /v1/event-series/{id}`.
4. RSVP transaction exactly as `lld.md` §3.5; `confirmed_count` maintained.
5. Reminder scan stub (log only) with idempotency keys.
6. Web: group page, create group, join/leave, RSVP with optimistic update,
   create-series form with timezone preview.

**Acceptance**

- T3, T4, T5, T6 green (see `testing_plan.md`).
- Generator run twice produces no duplicates; Feb 29 and DST unit tests pass.
- k6 `rsvp_race_script.js`: exactly one success on one free seat.

---

## Phase 3 — Matching (1 week)

**Goal:** async proposals that never block a request.

1. `POST /v1/match-requests`, `GET /v1/match-requests/{job_id}`.
2. Worker with `FOR UPDATE SKIP LOCKED`, cap 500 candidates, scoring and greedy
   formation from `lld.md` §5; job timeout 5 min.
3. Invitations: list, accept, decline, quorum group creation, 48 h expiry, re-match.
4. Web: preferences form, job progress, invitation accept/decline, group redirect.

**Acceptance**

- T7, T8 green. Seed 5,000 candidates → batch < 5 min.
- Duplicate request returns the existing job; no duplicate active job per user.
- No email or exact location exposed in match payloads.

---

## Phase 4 — Messaging (1 week)

**Goal:** ordered, idempotent group chat across instances.

1. Per-group sequence allocation in the insert transaction; publish after commit.
2. `POST /v1/realtime/tickets`; WS handler with ticket auth and membership check.
3. Redis pub/sub fan-out; history with `before_seq`; heartbeat and close codes.
4. Web: chat screen, optimistic send, ack/rollback, reconnect with backfill.

**Acceptance**

- T10, T11, T12 green; cross-instance p95 < 150 ms.
- Sequence survives Redis restart; duplicates return the original message.
- No token in the WS URL or logs.

---

## Phase 5 — Web App, PWA & E2E (1 week)

**Goal:** the product is usable and fast on a phone.

1. Complete E1–E5 flows with accessible labels, focus states and error toasts.
2. Route-level splitting; lazy map/chat; image proxy + responsive srcset.
3. Service worker: shell precache, discovery stale-while-revalidate (5 min),
   group detail network-first (30 s), offline banner, queued actions flush.
4. ETag/304 on discovery; skeletons; optimistic RSVP/message.
5. Playwright suite in CI; Lighthouse report saved to `PHASE_5.md`.

**Acceptance**

- E1–E5 green on 390 × 844.
- Lighthouse mobile performance ≥ 90; LCP < 2.5 s on throttled 4G.
- Offline: cached discovery renders; queued actions flush once on reconnect.

---

## Phase 6 — Observability, Load & Hardening (1 week)

1. Micrometer + Prometheus: discovery latency/hit rate, RSVP conflicts, job
   duration/backlog, WS connections, message rate; alerts from `hld.md` §10.
2. k6 L1–L6; baseline in `docs/perf/baseline.md`.
3. C1–C4 chaos checks; Redis-down discovery error rate < 1 %.
4. Security pass: auth matrix, rate limits, log PII scan, dependency scan.
5. Nightly `confirmed_count` reconciliation job with drift logging.
6. Update README, `PHASE_6.md`, and any contract drift in `lld.md`.

**Acceptance**

- 2-hour soak: no 5xx, heap growth < 5 %, p95 stable.
- Every phase acceptance check reproducible from a clean checkout.

---

## Deliverables checklist (end of P6)

- [ ] `docker compose up` (or documented native equivalent) starts api, web, PostGIS, Redis.
- [ ] Seeded demo city: 50 activities, 10 groups, 5 series.
- [ ] OpenAPI 3.0 at `/v3/api-docs`; Swagger UI available.
- [ ] Migrations `V1`/`V2` apply cleanly to an empty database.
- [ ] Integration tests T1–T12; E2E E1–E5; k6 L1–L6.
- [ ] `docs/perf/baseline.md` with measured numbers.
- [ ] README with Windows-native and Docker run instructions.
- [ ] `PHASE_1.md` … `PHASE_6.md` present.

---

## Working style

- Work phase by phase; small commits with imperative messages.
- At the end of each phase write `PHASE_N.md`: built, deferred, decisions, risks.
- If an ambiguity is not resolved by the docs, choose the option that preserves
  repeated in-person interaction, implement the smallest version, and record the
  decision in `PHASE_N.md` and the relevant doc.
- Never install global tools or change PATH; propose the change instead.
