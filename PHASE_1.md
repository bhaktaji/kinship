# Phase 1 — Foundation: Implementation & Acceptance Report

Date: 2026-11-04
Status: **P1 COMPLETE — all P1 acceptance checks PASS.**
Phase 2 has not been started. No commit was created (the workspace is not a git
repository) and nothing was pushed.

---

## 1. Infrastructure

Docker Desktop was already installed but not running; it was started, and the
existing Compose configuration was used. No software was installed and no
system service or PATH entry was changed.

| Component | Status | Detail |
|---|---|---|
| Docker Desktop | PASS | 29.9.2, Linux engine, Docker Compose v5.6.1 |
| WSL2 | PASS | `docker-desktop` distro running (version 2) |
| PostgreSQL | PASS | 16.4 in `postgis/postgis:16-3.4` |
| PostGIS | PASS | 3.4.3 |
| Redis | PASS | 7 (`redis:7-alpine`), PONG |
| Host ports | PASS | PostGIS 5433 → container 5432; Redis 6379 |
| Native PostgreSQL | PRESERVED | Windows service still owns 5432 and was not stopped |

`infra/docker-compose.yml` was changed only for the host-port conflict
(`"5433:5432"`). The named volume `kinship_kinship_pg` is kept; no volume was
deleted. Compose is invoked with `--env-file .env` because the file lives at the
repository root while the Compose file lives in `infra/`.

---

## 2. Database and migrations

A fresh database `kinship_acceptance` was created inside the container. The
existing `kinship` database and the volume were not modified or deleted.

| Check | Result | Evidence |
|---|---|---|
| V1 applied on empty DB | PASS | Flyway rank 1 "schema", success |
| V2 applied | PASS | Flyway rank 2 "indexes", success |
| Flyway status | PASS | 2 migrations, both successful |
| PostGIS extension | PASS | `3.4.3` |
| Application tables | PASS | 14 tables plus `flyway_schema_history` |
| Indexes | PASS | 52 indexes in `public`; key names verified |
| Foreign keys | PASS | 21 FKs |
| Check constraints | PASS | 133 checks |
| Unique indexes | PASS | 25 |

Key indexes verified by name: `idx_activities_location` (GIST),
`uq_events_series_start`, `idx_events_group_start`,
`uq_group_single_owner`, `uq_messages_group_seq`.

## 3. Seed data

| Item | Expected | Actual |
|---|---|---|
| Activities | 50 | **50** |
| Groups | 10 | **10** |
| Event series | 5 | **5** |
| Groups with a series | 5 | **5** |
| Interests | 12 | **12** |
| Occurrences per series | 8 | **8** |

Only five groups have series, so discovery returns five cards. The database
counts confirm this is seed shape, not a defect. Demo organizer
`demo@kinship.local` / `Demo12345!` logs in successfully; the acceptance
database's demo hash was updated to match the corrected seed.

## 4. Authentication and user APIs

A live acceptance script exercised the running API (50 checks, 50 passed):

| Check | Result |
|---|---|
| Registration (201), refresh cookie, access token | PASS |
| Duplicate email → 409 `EMAIL_TAKEN` | PASS |
| Invalid registration body → 400 `VALIDATION_ERROR` | PASS |
| Login (200) and cookie | PASS |
| `GET /v1/users/me` with bearer token | PASS |
| Protected endpoint without token → 401 | PASS |
| Refresh rotation (new cookie, usable access token) | PASS |
| Old refresh token reuse → 401 `INVALID_REFRESH` | PASS |
| Reuse revokes the token family | PASS |
| Logout (204) and refresh after logout → 401 | PASS |
| `GET /v1/interests` → 12 interests | PASS |
| `PATCH /v1/users/me` location, availability, interests | PASS |
| Invalid timezone / unknown interest → 400 | PASS |
| Login rate limit → 429 with `Retry-After` | PASS |
| CORS preflight for `http://localhost:3000` | PASS |

## 5. Discovery, caching and pagination

| Check | Result | Evidence |
|---|---|---|
| Discovery list | PASS | 5 seeded cards, each with group and next event |
| Activity detail | PASS | 200 with open group |
| First identical request | PASS | `X-Cache: MISS` |
| Repeated request | PASS | `X-Cache: HIT`, identical page |
| Redis cache TTL | PASS | key `geo:…:all:10`, TTL 113 s sampled (120 s configured) |
| Cursor pagination | PASS | 3 pages (2 + 2 + 1), no duplicate activity ids |
| Invalid cursor | PASS | 400 `INVALID_CURSOR` |
| Cursor/filter mismatch | PASS | 400 `INVALID_CURSOR` |
| Geographic filter | PASS | Munich search returns 0 |
| Category filter | PASS | all returned items `sport` |
| Group-size filter | PASS | `group_size_max=5` returns 0 (seed groups have 6) |
| Invalid latitude | PASS | 400 |
| Redis keys after run | PASS | 3 `geo:*` keys |

## 6. Automated tests

| Suite | Result |
|---|---|
| `mvn -q verify` | **PASS** |
| Unit tests | **11 run, 0 failures, 0 skipped** |
| Integration tests | **3 run, 0 failures, 0 skipped** — `DiscoveryIT` 2, `AuthFlowIT` 1 |
| Testcontainers execution | **PASS** — Failsafe summary: completed 3, errors 0, failures 0, skipped 0 |
| `npm run lint` | PASS — no warnings/errors |
| `npm run typecheck` | PASS |
| `npm run build` | PASS — 6 routes, 109 kB first-load JS |

## 7. k6 smoke (5 VUs, 2 minutes)

Executed with the `grafana/k6` container against the running API:

| Metric | Result |
|---|---|
| VUs / duration | 5 / 2m |
| HTTP requests | 600 (4.93/s) |
| Checks | 595, **100% succeeded** |
| Failed requests | **0.00%** |
| p95 latency | **14.97 ms** |
| Average latency | 11.48 ms |
| Max latency | 348.25 ms |
| Cache hit rate | **98.99%** (589/595) |
| Thresholds | all passed; k6 exit 0 |

## 8. Defects found and fixed

| Defect | Root cause | Fix |
|---|---|---|
| Registration rejected the demo/test password | `Demo1234!` is 9 chars; API minimum is 10. Seed and tests inherited it | Use `Demo12345!` in seed, ITs, README, PHASE report; updated acceptance DB demo hash |
| Testcontainers skipped with Docker 29 | Boot 3.3's Testcontainers 1.19.8 uses an API version below Docker 29's minimum 1.40 | Pin `testcontainers.version` 1.21.4 |
| PostGIS image rejected by Testcontainers 1.21 | Image compatibility must be declared | `.asCompatibleSubstituteFor("postgres")` in both ITs |
| `AuthFlowIT` failed on expected 401s | `HttpURLConnection` retries a 401 challenge in streaming mode | Use JDK `HttpClient` for expected 401 status checks |
| k6 failed with 59.66% errors | One token for 5 VUs exceeded the documented 120 req/min discovery limit | `setup()` registers one user per VU; rerun passed |
| Compose did not read root `.env`; port 5432 conflicted with native PostgreSQL | Compose project dir is `infra/`; host 5432 occupied | `--env-file .env`; map host 5433 |

No application business rule, schema, or API contract was changed to make tests
pass. The only production-code change was the demo seed password.

## 9. Deferred / out of scope

ETag/304, service worker, image optimization (P5); groups, RSVP, matching and
messaging (P2–P4); Prometheus/Grafana and load/chaos baselines (P6).

## 10. Remaining blockers and risks

- **Blockers: none for P1.**
- The acceptance database and test users remain in the Compose volume for
  inspection; they are disposable. The named volume was not deleted.
- Testcontainers is pinned to 1.21.4 for Docker 29 compatibility; revisit when
  Spring Boot's managed version catches up.
- The k6 script creates five users per run by design; this is test data, not a
  product defect.

## 11. Reproduction

~~~powershell
Copy-Item .env.example .env   # set a real KINSHIP_JWT_SECRET
docker compose --env-file .env -f infra/docker-compose.yml up -d postgres redis
# API (from api/):
$env:KINSHIP_DB_URL="jdbc:postgresql://localhost:5433/kinship_acceptance"
$env:KINSHIP_DB_USER="kinship"; $env:KINSHIP_DB_PASSWORD="kinship"
$env:KINSHIP_REDIS_HOST="localhost"; $env:KINSHIP_REDIS_PORT="6379"
$env:KINSHIP_SEED="true"
$env:KINSHIP_JWT_SECRET="<same value as .env>"
java -jar target/kinship-api-0.1.0-SNAPSHOT.jar

mvn -q verify
cd ../web; npm run lint; npm run typecheck; npm run build

docker run --rm -v "${PWD}/docs:/scripts" -e BASE_URL=http://host.docker.internal:8080 `
  grafana/k6 run /scripts/k6_discovery_smoke.js
~~~

P1 acceptance is complete; await approval before starting P2.
