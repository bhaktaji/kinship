# Kinship — P6 performance baseline

Measured on the local authoring machine against the compose production build.
Every number below comes from a file under `perf/results/`; the commands are
reproducible.

## 1. Environment

| Item | Value |
|---|---|
| Host | Windows 11, Docker Desktop 29.9.2, WSL memory limit 3.68 GiB |
| API | Spring Boot 3.3.13, Java 21 virtual threads, single container `kinship-api-1` |
| Database | PostGIS 16-3.4, `shared_buffers` default, `max_connections=100` |
| Cache / bus | Redis 7-alpine, `--save "" --appendonly no` (no persistence) |
| Web | Next.js 15.5.29 production container (idle during API tests) |
| Load generator | `grafana/k6:latest` container on the `kinship_default` network |
| Pool | Hikari 10 during L1–L4a, 20 afterwards (see §5) |
| Dataset | 5,271 users, 102 groups, 58 messages, 41 RSVPs, 12 match jobs |

Load accounts are 50 deterministic users (`load-N@perf.kinship.local`) created by
`perf/seed-users.mjs`; the matching fixture adds 5,000 candidates
(`perf/seed-5k.sql`). Tokens are 15-minute access tokens, refreshed by each VU
after 10 minutes.

## 2. How to reproduce

```bash
# once
node perf/seed-users.mjs                       # 50 accounts -> perf/.users.json
docker exec -i kinship-postgres-1 psql -U kinship -d kinship < perf/seed-5k.sql

# load (k6 runs inside the compose network, scripts mounted read-only)
PROJ=$(cygpath -m "$PWD")
docker run --rm --network kinship_default -v "$PROJ/perf:/perf" \
  -e API=http://api:8080 -e USERS_FILE=/perf/.users.json \
  grafana/k6 run --summary-export=/perf/results/02-baseline.json /perf/k6/02-baseline.js

node perf/summary.mjs                          # normalised table of all runs
```

## 3. Discovery and profile load

`discovery()` = `GET /v1/activities?lat=52.52&lng=13.405&radius_km=10&limit=20`,
`me()` = `GET /v1/users/me`. Both are authenticated; discovery is capped at
**120 requests/minute per user** by `RateLimitService`.

| Stage | VUs | Duration | Requests | Throughput | p95 (all) | discovery p95 | Failures |
|---|---|---|---|---|---|---|---|
| L1 smoke | 5 | 30 s | 275 | 9.0/s | 42.5 ms | 43.6 ms | 0 % |
| L2 baseline | 20 | 3 min | 13,976 | 77.5/s | 7.0 ms | 7.2 ms | 0 % |
| L3 moderate | 50 | 5 min | 95,360 | 317.6/s | 6.2 ms | 5.9 ms | 18.3 % (17,446) |
| L4a stress, pool 10 | 10→200 | 7 min | 409,320 | 974/s | 48.1 ms | 36.8 ms | 40.2 % |
| L4b stress, pool 20 | 10→200 | 7 min | 52,416 | 125/s | 2,824 ms | — | 6.5 % |

Interpretation:

- Up to 20 VUs the API is comfortably inside the 300 ms p95 target (7 ms), with
  zero failures. The first iteration of a cold VU pays ~2.4 s of JIT/warm-up.
- **The L3 and L4a failures are the rate limiter, not capacity.** Discovery is
  capped at 120 requests/minute per user. 50 VUs iterate at ~6.3 requests/s each
  (~380/minute per user) so the excess is rejected with 429 while p95 stays at
  6 ms; the baseline stage sits just under the cap at ~116/minute per user and
  shows zero failures.
  `perf/status-probe.mjs` confirms it directly: a 200-request burst from one
  account at concurrency 40 returned `{"200":120,"429":61,"ERR:TypeError":19}`
  — exactly 120 successes, then 429 for every later request. The 19 `TypeError`
  entries are client-side `fetch` failures under a 40-way burst, not API statuses.
- The two stress runs are not directly comparable because the k6 container and
  the API share one 3.68 GiB WSL VM. L4a reached 974 req/s with 40 % rejected;
  L4b spent the same budget on latency (2.8 s p95) at 125 req/s with 6.5 %
  rejected. Both are saturation signals; neither is a capacity number, because the
  generator competes with the system under test and the limiter rejects a large
  share of the load before it reaches the database.

### Pool sizing at constant concurrency

The stress failure rate could not attribute pool behaviour, so the pool was
compared directly with 1,200 `GET /v1/users/me` calls at concurrency 150 (no rate
limiter on that route), against the same database:

| Pool | Completed | Duration | Throughput | Client failures |
|---|---|---|---|---|
| 10 | 1,114 / 1,200 | 33.5 s | 35.8/s | 86 |
| 20 | 1,200 / 1,200 | 4.6 s | 262/s | 0 |

The 20-connection pool is ~7× faster at this concurrency and produced no failed
requests, which is the evidence for the pool change. The 2× 503
(`database_unavailable`) events seen in API logs during L4a were incidental, not
the cause of that stage's 40 %.

## 4. Business invariants under load

### RSVP capacity race (L6)

One public group (20 seats), 19 members, one event with **capacity 2**, all
`GOING` requests issued in the same instant:

| Outcome | Count |
|---|---|
| 200 GOING | **2** |
| 409 `EVENT_FULL` | 17 |
| anything else | **0** |
| final `confirmed_count` | **2 / 2** |

The seat invariant holds under contention and the losers get a typed conflict,
not a 5xx.

### Matching with 5,000 candidates

`perf/seed-5k.sql` inserts 5,000 users in the Berlin r7 cell with three interests
and Thursday-evening availability; the requester is a fresh account with the same
availability.

| Measure | Value |
|---|---|
| Request → job id | 57 ms |
| Job QUEUED → DONE | 1,205 ms (poll every 500 ms) |
| Server-side job duration | 0.755 s (`kinship_match_job_duration_seconds`) |
| Candidate cap | 500 (query `LIMIT 500`) |
| Result | 1 match group, 1 invitation, `DONE` |
| Acceptance target | < 5 min — **PASS** |

The 5,000-user fixture changes the candidate scan, not the group formation, which
is bounded at 500 candidates by `MatchRepository.candidates`.

### Soak (20 minutes)

`05-soak.js` at 25 VUs for 20 minutes:

| Measure | Value |
|---|---|
| Iterations | 29,289 (24.4/s) |
| Requests | 58,628 (~48.9 rps) |
| Failures | **0 / 58,628 (0.00 %)** |
| p95 / avg / max | 15.7 ms / 11.5 ms / 7.87 s |
| API RSS | 1.044 GiB → 1.029 GiB, flat across 30 s samples |
| Restarts | none |

The 2-hour acceptance soak was **not** run; this covers warm-up, GC settling and
29k iterations only.

## 5. Realtime

`perf/ws-load.mjs` opens authenticated group sockets, posts three messages over
HTTP, and measures delivery per socket. Each socket uses a fresh single-use
ticket.

| Scenario | Sockets ready | Delivery | p95 (steady state) | Reconnects backfilled |
|---|---|---|---|---|
| Single instance `:8080` | 50/50 | 150/150 | 46 ms | 25/25 |
| Cross-instance: sockets on `:8081`, sends via `:8080` | 50/50 | 150/150 | 45 ms | 25/25 |

Cross-instance delivery goes through Redis pub/sub; the second instance was
started with the same JWT secret and shared database/Redis. The first round pays
~200 ms of connection/JIT warm-up, later rounds settle at 30–46 ms.

## 6. Failure injection

`perf/chaos.sh` — nothing is deleted; containers are stopped and restarted.

| Action | Observed |
|---|---|
| Redis stopped | `/readyz` 503 (0.33 s), login 503, WS ticket 503, discovery 200 in **1.04 s** (fail-open cache), message 201 in 0.67 s (published live fails, persisted anyway), no API restart |
| Redis restarted | `/readyz` 200 after 2 s, login 200, ticket 201, history 200 |
| PostgreSQL stopped | `/readyz` 503 (3.1 s), `/healthz` 200, discovery 200 in 0.22 s (served from Redis), login/message 503, API container `running`, `restarts=0` |
| PostgreSQL restarted | `/readyz` 200 after 4 s, discovery and history normal, row counts unchanged |
| Stale `RUNNING` match job (12 min old) + API restart | job moved to `FAILED / JOB_TIMEOUT` by `recoverStale()` on the first sweep |

Before the timeout change, the Redis outage added **7.26 s** to a discovery call
and login returned no response at all to the client; after lowering the Redis
command/connect timeout to 300 ms the same probe returns 1.04 s and a clean 503.
That is the only code change made because of a Gate B measurement.

## 7. Backup and restore

`perf/backup-restore.sh`:

| Step | Result |
|---|---|
| `pg_dump -Fc` of the live database | 520,488 bytes in 18 s |
| Restore into scratch database `kinship_restore` | 60 s |
| Row counts after restore | users 5,271 · groups 102 · messages 58 · rsvps 41 · match_jobs 12 — **identical** |
| Spot check | latest message sequence 6 |
| Cleanup | scratch database dropped; live database untouched |

This proves the dump/restore path. There is still no scheduled backup, no
off-host copy and no point-in-time recovery.

## 8. Observability

`/actuator/prometheus` now exposes JVM, HTTP, Hikari and custom meters:

```
kinship_discovery_requests_total{cache="hit"|"miss"}
kinship_rsvp_conflicts_total
kinship_messages_sent_total
kinship_match_queue_depth
kinship_ws_connections
kinship_match_job_duration_seconds{outcome}
http_server_requests_seconds / hikaricp_connections_* / jvm_*
```

Access requires `X-Metrics-Token`; without it, or with a wrong token, the endpoint
answers 404 (verified: `no_token=404`, `wrong=404`, `valid=200`). Alert rules and
a Prometheus server are not part of the compose stack, so the meters are visible
but nothing pages on them yet.

## 9. Deferred / not measured

- 2-hour soak (P6 acceptance): a **20-minute** soak was run; see `PHASE_6.md`.
- Slow-consumer isolation: no per-socket send queue exists, so a stalled client
  can still hold its group's broadcast loop.
- Multi-host fan-out, host failure, Postgres failover, PITR: single host only.
- TLS termination and reverse-proxy behaviour: no proxy in the stack.
- Matching at 5,000 users **concurrently** (many requesters at once): one
  requester at a time; the worker drains serially by design.
- Java CVE scan of the dependency tree: **not completed.** Trivy read its
  vulnerability database but found no analysable language files in the Spring
  Boot fat jar or its unpacked `BOOT-INF/lib` (92 jars) on this Docker
  Desktop/WSL setup, so no Maven CVE result exists. `npm audit` on the web app
  reports 0 critical and 0 high.
