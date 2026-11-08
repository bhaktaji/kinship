# Kinship — project closure

Status: **P0–P6 complete for the local, single-host deployment.** This document is
the handover record: what was built and verified, what was not, and exactly what
remains before any production use. It is **not** a statement of production
readiness or security certification.

Evidence lives in `PHASE_1.md` … `PHASE_6.md`, `SECURITY_AUDIT.md`,
`SYSTEM_DESIGN_REVIEW.md`, `docs/perf/baseline.md` and `perf/results/`.

## 1. Completed phases

| Phase | Scope | Evidence |
|---|---|---|
| P0 | Foundation: repo, compose, Flyway, CI skeleton | `PHASE_1.md` |
| P1 | Auth, profiles, interests, cached discovery, seed data | `PHASE_1.md` |
| P2 | Groups, membership, events, RSVP with seat capacity | `PHASE_2.md` |
| P3 | Asynchronous matching, invitations, match jobs | `PHASE_3.md` |
| P4 | Persisted group chat over WebSocket, tickets, backfill | `PHASE_4.md` |
| P5 | Mobile-first web app, PWA, E2E suite, security sign-off | `PHASE_5.md`, `SECURITY_AUDIT.md` |
| P6 | Load, soak, realtime, failure injection, backup, observability | `PHASE_6.md`, `docs/perf/baseline.md` |

## 2. Verified results

### Correctness and security

- Backend: **25 unit + 18 integration tests**, 0 failures (`mvn -B -ntp verify`).
- Security probes: **24/24 pass**, including CSP/headers, refresh-cookie flags,
  `/actuator/env` 404, metrics hidden without `X-Metrics-Token`, duplicate-message
  idempotency.
- Web: Playwright E2E **16/16** at the P5 gate; lint, typecheck and production
  build pass. The P6 work did not change the UI, so E2E was not re-run in P6.
- Live behaviour verified: sign-in, discovery with cache HIT/MISS, groups/events,
  RSVP capacity, matching, chat delivery and reconnect backfill.

### Performance (local, 3.68 GiB WSL VM)

| Workload | Result |
|---|---|
| Baseline 20 VUs / 3 min | 13,976 requests, 0 % failed, p95 7 ms |
| Moderate 50 VUs / 5 min | 95,360 requests, p95 6 ms; 18.3 % rejected as **429 by the 120/min limiter** |
| Stress 200 VUs | saturation signal only (974 rps at 40 % rejected; generator-bound) |
| Soak 20 min / 25 VUs | 58,628 requests, **0 % failed**, RSS flat 1.044→1.029 GiB |
| Matching, 5,000 candidates | 1 group + 1 invitation, 1.2 s end to end (target 5 min) |
| Realtime, 50 sockets | 150/150 frames, p95 46 ms local and 45 ms cross-instance |
| RSVP race, 19 members / 2 seats | exactly 2 successes, 17 typed conflicts, 0 unexpected |
| Redis stop | discovery 200 in 1.04 s, login 503, recovery 2 s, no restart |
| Postgres stop | `/readyz` 503, discovery from cache, recovery 4 s, no data loss |
| Backup → restore | 520 KB dump, 60 s restore, identical counts, scratch DB dropped |

## 3. Outstanding acceptance criteria

| Item | State |
|---|---|
| 2-hour soak | **NOT TESTED** — a 20-minute soak passed; multi-hour behaviour unproven |
| Java dependency CVE scan | **NOT COMPLETED** — Trivy could not parse the Spring Boot fat jar; `npm audit` is 0 critical / 0 high |
| Slow-consumer isolation | **NOT TESTED** — no bounded per-socket send queue exists |
| Multi-host / TLS / proxy / PITR | **NOT TESTED** — single host, HTTP only |
| Alerting/dashboards | **NOT DONE** — meters exposed, nothing scrapes or pages |
| GitHub Actions pipeline | **NOT VERIFIED** — workflow exists but was never executed on a push |

## 4. Known security risks

Accepted and documented in `SECURITY_AUDIT.md` (A1–A8). The material ones:

- No TLS termination or HSTS in the local stack; cookies are `Secure=false`
  locally by design.
- Single host, no reverse proxy, no rate limit at the edge; per-user limits live
  in the API and reset every minute (fixed window).
- Redis has no persistence: a restart signs everyone out and drops pending
  tickets. Documented as the recovery model.
- `/actuator/prometheus` uses a shared static token (`local-metrics-token` by
  default) rather than network isolation; a real deployment must override it and
  restrict reachability.
- No independent penetration test; no Java CVE result.

## 5. Known operational risks

- No scheduled backup, no off-host copy, no point-in-time recovery. The restore
  path is manual and was rehearsed once.
- No metrics scraping, alert rules or tracing; queue depth and WebSocket counts
  are visible only if someone queries the endpoint.
- Matching drains serially in one worker; fine at the measured 5,000-candidate
  scale, but a backlog has no autoscaling.
- Services run either in compose or natively via `startup.bat`; the two modes are
  mutually exclusive on ports 8080/3000. The launcher detects and reports this,
  but does not migrate a running compose stack.

## 6. Deployment prerequisites

- Docker Desktop / Docker Engine with Compose v2, WSL2 backend on Windows.
- Java 21 (Temurin 21 tested), Maven 3.9+ (no Maven wrapper is committed).
- Node.js 20+ and npm (Node 26 used locally; Next 15 supports 20/22 LTS).
- Free ports: **8080** (API), **3000** (web), **5433** (Postgres host mapping),
  **6379** (Redis).
- `.env` at the repository root with a `KINSHIP_JWT_SECRET` of at least 32
  characters. `.env.example` lists every supported variable.
- Images: `postgis/postgis:16-3.4`, `redis:7-alpine` (pulled by compose).
- No secrets are stored in the launcher; it reads `.env` and never prints values.

## 7. Exact remaining actions before production

1. Run the **2-hour soak** at the expected peak concurrency and record memory,
   GC and failure rate.
2. Obtain a **Java dependency CVE scan** (OWASP dependency-check or a working
   Trivy/Snyk run) and triage Critical/High findings.
3. Add a **bounded per-socket send queue** and test slow-consumer isolation.
4. Put the stack behind **TLS and a reverse proxy**; set `KINSHIP_COOKIE_SECURE=true`
   and restrict `/actuator/*` to the monitoring network.
5. Replace the metrics token with a secret from the deployment's secret store and
   add Prometheus scraping plus alert rules for error rate, p95, pool saturation,
   queue depth and `/readyz`.
6. Schedule **`pg_dump` to off-host storage**, add retention, and rehearse a
   restore from that copy (the current rehearsal is same-host).
7. Decide the Redis durability model (AOF) or formally accept re-login on restart.
8. Execute the **GitHub Actions** pipeline on a pushed branch and fix any drift
   from the local checks.
9. Re-run the Playwright E2E suite against the deployment candidate.

## 8. Starting the project locally

Double-click **`startup.bat`** at the repository root (or run it from any
directory). It is the single entry point and delegates to
`scripts\startup.ps1`.

What it does, in order:

1. Verifies Docker, Java 21, Maven, Node.js 20+ and npm are available.
2. Loads `.env`; creates it from `.env.example` with a generated JWT secret if it
   is missing.
3. Checks Docker Engine, launching Docker Desktop from its standard path if
   needed (up to 4 minutes).
4. Starts **only** `postgres` and `redis` from `infra/docker-compose.yml` and
   waits for both health checks. Existing volumes are reused; nothing is reset.
5. Starts the API natively (`mvn spring-boot:run`) against the compose host port
   **5433**, unless an API already answers `/readyz`.
6. Starts the web app natively (`npm run dev`, pinned to port 3000), unless it
   already serves.
7. Waits for `http://localhost:8080/readyz` and `http://localhost:3000`.
8. Prints the URLs, health, demo login and log/PID locations.

| URL | Purpose |
|---|---|
| http://localhost:3000 | Web app |
| http://localhost:8080 | API |
| http://localhost:8080/readyz | Readiness (DB + Redis) |
| http://localhost:8080/swagger-ui.html | API docs |
| localhost:5433 | PostGIS (container port 5432) |

Demo login (seeded, documented in `README.md`):
`demo@kinship.local` / `Demo12345!`

Logs and PIDs: `logs\backend.log`, `logs\frontend.log`, `logs\*.pid`. They are
git-ignored. The processes keep running after the window closes; stop them with:

```powershell
Get-Content logs\backend.pid  | ForEach-Object { Stop-Process -Id $_ -Force }
Get-Content logs\frontend.pid | ForEach-Object { Stop-Process -Id $_ -Force }
```

Running `startup.bat` again is safe and idempotent: it reuses healthy services,
starts nothing twice, and exits non-zero with the failing step if something is
wrong.

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `[FAIL] 'mvn' is not on PATH` | Install Maven 3.9+ or add it to PATH. No wrapper is committed. |
| `Docker engine did not become ready` | Start Docker Desktop manually and wait for "Engine running"; WSL2 backend required. |
| `Port 8080 is in use but /readyz is not healthy` | The compose `api` container owns it. Run `docker compose --env-file .env -f infra/docker-compose.yml stop api`, then re-run. |
| Same for port 3000 | Compose `web` container. `docker compose --env-file .env -f infra/docker-compose.yml stop web`. |
| `.env` secret error | `KINSHIP_JWT_SECRET` must be ≥ 32 characters and must not contain the example placeholder. |
| Postgres will not start | Host port 5433 already taken, or the volume is from an incompatible major version. Inspect `docker compose ... logs postgres`; never delete the volume. |
| `npm ci` fails | Check Node ≥ 20 and network/proxy access; see `logs\npm-ci.log`. |
| Web answers on a random port | Older runs could fall back when 3000 was busy; the launcher now pins `-p 3000` and fails early if the port is occupied. |
| First page load is slow | `next dev` compiles on demand; the launcher waits for the first 200 before printing the summary. |
| Backend ready but API calls fail in the browser | Check `NEXT_PUBLIC_API_BASE_URL` in `.env` is `http://localhost:8080` and re-run (it is baked at dev-server start). |
| Need to change configuration | Edit `.env`, stop the API/web processes from `logs\*.pid`, re-run `startup.bat`. |

## 10. Verification of this closure

All runs below were executed from `C:\`, so the batch had to resolve its own
repository root rather than rely on the working directory.

- **First full run:** health-waited Postgres/Redis, started the API with
  `mvn spring-boot:run` and the web app with `npm run dev -- -p 3000`; exit **0**,
  `/readyz` **200**, web **200**, demo login **200**.
- **Second run:** reused the healthy API and the serving web app, started nothing;
  exit **0**.
- **Third run** (API stopped first): started only the API, reused the web server;
  exit **0**.
- **Negative test:** a dummy HTTP listener returning 503 on port 8080 made the
  script stop at `backend start` with an explicit message and exit **1**, with
  zero Java processes started. This test exposed a real bug — the original port
  probe missed the listener and started a second API — which was fixed (HTTP
  probe plus TCP connect on IPv4/IPv6) and re-tested.
- **Data:** 5,273 users, 50 activities and 103 groups before and after, and the
  same `kinship_kinship_pg` volume. No volume, database or destructive Docker
  command was executed.

## P7 — Engagement, onboarding and community experience

Status: **implemented and verified.** Added onboarding fields, configurable group join approval,
persistent notifications, post-activity reviews and image uploads. Flyway V5 applied; backend/frontend
restarted; browser and API journeys pass.

New migrations: `V5__p7_engagement.sql`.

Backend modules added: `notification`, `review`, `image`; extended `user`, `group`.

Frontend routes added: `/onboarding`, `/notifications`; group join request and review UI; profile photo upload.

Tests: `mvn verify` (25 unit + 18 integration, 0 failures) and `npm run lint/typecheck/build` pass.

Known limitations: local filesystem image storage by default; real-time notification delivery uses polling; browser push not implemented; slow-consumer and multi-host not tested.

Not exercised, and therefore not claimed:

- A repository path containing spaces (this workspace has none; every path in the
  scripts is quoted, but that was not run).
- Docker Desktop cold start (the engine was already running).
- The `npm ci` fallback (`web\node_modules` already existed).
- Automatic `.env` creation from `.env.example` (a valid `.env` already existed).
