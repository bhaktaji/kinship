# Kinship — System Design Review

Scope: existing P1–P5 architecture assessed against realistic growth. No
infrastructure was added and no code was restructured for this review. Load
tests, cross-instance tests and failure injection are **Gate B** work and are
marked **NOT TESTED** here; where a number is quoted it comes from an existing
run recorded in this repository.

## 1. Current architecture

| Layer | Implementation | Evidence in repo |
|---|---|---|
| API | Spring Boot 3.3.13, Java 21, virtual threads enabled, modular monolith (auth, user, group, event, match, messaging, activity) | `api/pom.xml`, `api/src/main/resources/application.yml` |
| Database | PostgreSQL 16 + PostGIS 3.4, Flyway migrations V1–V4, no ORM — `JdbcClient` with bound parameters | `db/migration/*.sql`, `*Repository.java` |
| Cache / session | Redis 7: opaque refresh tokens, WS tickets, fixed-window rate limits, `group:<id>` pub/sub | `RefreshTokenService`, `WsTicketService`, `RateLimitService`, `RedisGroupPublisher` |
| Realtime | Spring WebSocket, single-use Redis ticket, per-instance `GroupSessionRegistry`, Redis fan-out, 30 s ping | `WebSocketConfig`, `TicketHandshakeInterceptor`, `GroupSessionRegistry`, `WsHeartbeat` |
| Jobs | In-process `@Scheduled`: matching poll (10 s), expiry + stale recovery (10 min), occurrence generation | `MatchWorker`, `OccurrenceGenerator` |
| Web | Next.js 15.5.29 standalone, React 19, TanStack Query, service worker (`public/sw.js`), IndexedDB outbox | `web/Dockerfile`, `web/package.json` |
| Delivery | Docker Compose: PostGIS, Redis, API, web; API/web run as non-root; healthchecks gate startup | `infra/docker-compose.yml` |

The API is stateless apart from two things: the in-process WebSocket session
registry and the scheduler. JWT access tokens are verified without a database
read (`JwtService`), so any instance can serve any request.

## 2. Measured baselines that exist today

| Metric | Value | Source |
|---|---|---|
| Backend unit + integration tests | 23 unit, 18 integration (Testcontainers PostGIS + Redis) | `mvn -B -ntp verify` (this sign-off run) |
| Browser journeys | 16 Playwright tests, serial, 390×844 against the compose production build | `PHASE_5.md` §7.2 |
| Discovery p95 (Lighthouse mobile, signed-out shell) | LCP 0.8–1.0 s; TBT 170–700 ms on a loaded host | `docs/perf/lighthouse-mobile.json` |
| Message send idempotency | 20 concurrent sends → unique, contiguous sequence ids; replayed `client_msg_id` returns the original message | `MessagingIT`, `duplicate-message` probe |
| Refresh rotation concurrency | 4 simultaneous refreshes of one token → exactly one HTTP 200 | `AuthFlowIT.concurrentRefreshOfTheSameTokenHasExactlyOneWinner` |
| Cached discovery ETag | 304 path exercised in E2E; Redis-backed cache | `DiscoveryCache`, `DiscoveryIT` |

Everything else in this document is a design-stage analysis with the failure
point named explicitly, not a measured limit.

## 3. Limitations and decisions

### L1 — Chat send serialises on the group row
**Design.** `MessageService.send` takes `SELECT … FOR UPDATE` on `groups` for the
group, then increments `last_message_seq`.
**Evidence.** Concurrent-send test passes with 20 parallel sends and strictly
contiguous sequence numbers (`MessagingIT`).
**Expected failure point.** All sends in one group queue behind that row lock, so
one group's throughput is bounded by lock hold time (~one round trip). Groups do
not block each other; a hot 6-person group is not a realistic bottleneck.
**Recommendation if it becomes one.** Batch sequence allocation (e.g. allocate a
block of ids per send) or move sequencing to a Redis counter with periodic
reconciliation. Not justified at the measured scale.
**Trigger.** p95 send latency > 200 ms with < 50 concurrent senders, or lock wait
visible in `pg_stat_activity` for > 5% of time.

### L2 — Matching is a single in-process worker draining serially
**Design.** `MatchWorker` claims one job with `FOR UPDATE SKIP LOCKED`, processes
it fully, then loops. Candidate discovery is H3 k-ring 2 + `ST_DWithin`, capped
at 500 rows, then filtered on shared groups and availability overlap.
**Evidence.** `MatchingIT` (4 users → invitations) and E5 (end-to-end invitation
→ confirmed group). No 5,000-user run has been executed.
**Expected failure point.** With 5,000 *registered* users the candidate cap and
k-ring still bound the work (≤ 500 rows scored per job), so per-job cost stays
roughly constant — the queue length, not the job cost, is what grows. At 5,000
*simultaneously active* users each queueing a job, a single worker at one job per
poll cycle is the limit; queue drain time grows linearly.
**Recommendation.** Run multiple worker instances (claim query already supports
it) and raise `@Scheduled` frequency before touching the algorithm.
**Trigger.** Median queue wait > 60 s (job `created_at` → `started_at`) at
p95 daily load, or > 100 queued jobs.

### L3 — Candidate completeness is capped by design
**Design.** 500-candidate cap, k-ring 2 (~±20 km at r7), shared-group exclusion,
`MIN_GROUP_SIZE = 4`.
**Evidence.** No completeness measurement exists.
**Expected failure point.** In dense areas, eligible candidates beyond the 500
nearest are never considered; in sparse areas (< 3 compatible neighbours) jobs
fail with `NO_CANDIDATES`, which the UI surfaces.
**Recommendation.** Measure matches-per-request and `NO_CANDIDATES` rate before
changing the cap; if it is high in dense cells, page candidates by distance
rather than raising the cap.
**Trigger.** `NO_CANDIDATES` > 20% in any city cell with > 200 active users.

### L4 — Redis holds sessions, tickets and rate limits without persistence
**Design.** Compose runs `redis-server --save "" --appendonly no`.
**Evidence.** `infra/docker-compose.yml`.
**Expected failure point.** A Redis restart logs every user out (refresh tokens
gone), drops in-flight WS tickets, and resets rate-limit windows; chat history in
Postgres is unaffected and clients recover by signing in again.
**Gate B measurement.** With Redis stopped: `/readyz` 503 in 0.33 s, login 503,
WS ticket 503, discovery still 200 because the cache fails open — 1.04 s after the
command timeout was reduced to 300 ms (7.26 s with the old 2 s timeout). Recovery
once Redis restarted: `/readyz` 200 in 2 s, login/ticket/history all normal, no
API restart and no lost Postgres data.
**Recommendation.** Enable AOF when Redis is treated as durable session storage,
or accept re-login as the recovery model and document it for operations.
**Trigger.** Any deployment where a Redis restart is not an accepted
"everyone signs in again" event.

### L5 — Realtime fan-out relies on Redis pub/sub, untested across instances
**Design.** Each instance subscribes to `group:<id>` only while it holds local
sessions; published frames are broadcast to local sessions after re-checking
active membership per frame.
**Evidence.** Single-instance E2E two-browser delivery (`E4`), and the new
`GroupSessionRegistryTest` for the authorization filter. Two API instances were
never run.
**Expected failure point.** Cross-instance delivery depends on pub/sub ordering
and on every instance holding a subscription for the group; a missed subscribe
(instance started after the last join) means that instance's users see messages
only after a history refresh. Slow consumers block the broadcast loop because
writes are synchronous per session.
**Gate B measurement.** Two API instances (8080/8081) sharing Redis and Postgres:
50 sockets connected to instance B, messages posted to instance A — 150/150 frames
delivered across three rounds, p95 45 ms, 25/25 reconnects backfilled to the
current sequence. Slow-consumer isolation is still **NOT TESTED** (no bounded
send queue exists).
**Recommendation.** Add a bounded send timeout/queue per session before claiming
slow-consumer isolation; subscription-on-join holds for the two-instance test.
**Trigger.** More than one API instance in production.

### L6 — Connection pool and per-instance fan-out limits
**Design.** Hikari `maximum-pool-size: 20` (was 10), `connection-timeout: 3 s`.
**Evidence.** `application.yml`; pool exhaustion surfaces as
`DEPENDENCY_UNAVAILABLE` 503 from `GlobalExceptionHandler`.
**Gate B measurement.** 1,200 `GET /v1/users/me` calls at concurrency 150 (no
rate limiter on that route) against the same database: pool 10 completed
1,114/1,200 in 33.5 s (35.8 rps) with 86 client-side failures; pool 20 completed
1,200/1,200 in 4.6 s (262 rps) with none. That is the evidence for raising the
pool. The 40 % rejections in the 200-VU stress stage were shown to be 429s from
the 120/min per-user limiter, not pool exhaustion — the stage does not measure
this limit.
**Expected failure point.** Above ~20 concurrent slow queries per instance
requests wait up to 3 s then fail fast. Matching and chat send hold locks, so
they are the first to feel it.
**Recommendation.** Keep the pool at 20 per instance and scale horizontally
rather than raising it further; re-measure if query time changes.
**Trigger.** `connection-timeout` 503s > 0.1% of requests.

### L7 — WebSocket resource limits
**Design.** No cap on sockets per user or per group; heartbeat every 30 s; server
buffers whole frames in memory; no message-size guard for inbound frames beyond
the 2,000-character body check.
**Evidence.** Code inspection (`GroupSessionRegistry`, `GroupWebSocketHandler`);
no flood test has been run.
**Expected failure point.** One client can open many sockets to the same group;
broadcast cost grows with socket count, and a slow reader can stall that group's
fan-out.
**Recommendation.** Per-user socket cap and send timeout in Gate B.
**Trigger.** Any public deployment with untrusted clients.

### L8 — Single database, no backups, no read replicas
**Design.** One PostGIS container with a named volume; no scheduled backup job,
no PITR configuration, no replica.
**Evidence.** `infra/docker-compose.yml` defines only the volume.
**Gate B measurement.** `perf/backup-restore.sh` dumped the live database
(520,488 bytes, 18 s), restored it into a scratch database in 60 s, and every
count matched exactly (5,271 users, 102 groups, 58 messages, 41 RSVPs, 12 match
jobs, latest message sequence 6). The scratch database was dropped; the live
database was never modified. This proves the backup path, not an automated
schedule or off-host copy — neither exists yet.
**Expected failure point.** Host loss still loses all data; the restore is
manual and has never been run from an off-host copy.
**Recommendation.** Schedule `pg_dump` to object storage off the host, keep the
restore rehearsal as the acceptance check, and add replicas only when read load
demands them.
**Trigger.** Before the first real user data is accepted.

### L9 — Observability is logs + health endpoints only
**Design.** Structured JSON logs with a request id (`RequestIdFilter`), `/healthz`,
`/readyz`, `/actuator/health`; actuator exposes only health and info.
**Evidence.** `application.yml`, `logback-spring.xml`; `actuator-env` probe
returns 404 even authenticated.
**Gate B measurement.** Micrometer now exports JVM, HTTP, Hikari pool and
business meters (`kinship_discovery_requests_total{cache}`, `kinship_rsvp_conflicts_total`,
`kinship_messages_sent_total`, `kinship_match_queue_depth`,
`kinship_ws_connections`, `kinship_match_job_duration`) through
`/actuator/prometheus`, gated by `X-Metrics-Token`. A nightly
`CountReconciliationJob` logs and repairs `events.confirmed_count` drift.
**Expected failure point.** No traces, no alert rules and no scraper are
configured: the meters exist but nothing pages on them, and process-level GC
pressure is only visible through JVM meters.
**Recommendation.** Point Prometheus at the management port, add alert rules from
`hld.md` §10, and add tracing when a request spans more than one service.
**Trigger.** Before the first production deployment.

### L10 — Delivery model is a single compose host
**Design.** One host, four containers, no TLS termination, no rollback beyond
`docker compose up` with a previous image tag.
**Evidence.** `docker-compose.yml`, CI e2e job.
**Expected failure point.** Host maintenance is downtime; no zero-downtime
deploy or automatic rollback.
**Recommendation.** Deploy behind a reverse proxy with two API containers and
health-gated rolling replacement when availability targets require it.
**Trigger.** Any availability SLO above "best effort".

### Deliberately not added
Kafka, Kubernetes, microservices, read replicas and sharding have no measured
justification: single-group chat, a 10 s matching poll and one Postgres instance
are far from their limits in the current tests. Each has a named trigger above.

## 4. Data model and query notes

- Keyset pagination everywhere it matters: discovery (`CursorCodec`), groups
  (`joined_at`, `group_id`), messages (`before_seq`).
- Indexes match the hot paths: `idx_activities_location` (GiST),
  `idx_activities_h3_category`, `uq_messages_group_seq`,
  `uq_messages_client_id`, `idx_match_jobs_claim` (partial, `SKIP LOCKED`),
  `uq_match_jobs_active` (one active job per user).
- Uniqueness constraints carry business rules: one active owner per group, one
  invitation per user per proposal, one series per (group, title, starts_on), one
  occurrence per (series, start_time).
- `confirmed_count` is denormalised and updated in the same transaction as the
  RSVP row; capacity is enforced under `FOR UPDATE` on the event.

## 5. Assessment status

| Area | Status |
|---|---|
| Modular boundaries, stateless API design | Reviewed, sound for current scale |
| Data model, indexes, idempotency constraints | Reviewed, sound |
| Query plans under large datasets | **NOT TESTED** (Gate B) |
| 5,000-user matching throughput | **NOT TESTED** (Gate B) |
| Two-instance realtime / Redis failure | **NOT TESTED** (Gate B) |
| Postgres/Redis outage recovery | **NOT TESTED** (Gate B) |
| Backup and restore | **NOT TESTED**; no automation exists |
