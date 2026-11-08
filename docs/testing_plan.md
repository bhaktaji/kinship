# Testing Plan
## Recurring-Activity Friendship Platform ("Kinship")

Version: 1.1 | Status: Reviewed for implementation | Stack: JUnit 5 · Testcontainers · Playwright · k6

---

## 1. Strategy

| Layer | Scope | Tools | Gate |
|---|---|---|---|
| **Unit** | Recurrence, matching score, H3/cache keys, authorization rules, cursor codec | JUnit 5, AssertJ, Mockito | ≥ 80 % line coverage for `event`, `match`, `activity` domain classes |
| **Integration** | Transactions, SQL, Redis, HTTP contract | Spring Boot Test, Testcontainers (PostGIS + Redis), MockMvc | Every critical path below |
| **Contract** | OpenAPI shape and status codes | springdoc output + Spring MockMvc assertions | All public endpoints |
| **E2E** | Five core user journeys on a mobile viewport | Playwright | P5 |
| **Load** | Discovery, RSVP race, messaging fan-out, soak | k6 | P6 |
| **Chaos** | Redis down, API restart, socket drop | Testcontainers lifecycle + k6 | P6 |

Test IDs below are referenced by phase prompts and `PHASE_N.md` reports.

---

## 2. Phase Gates

| Phase | Must be green before exit |
|---|---|
| P1 | U1–U4, U12; T1, T2, T9; auth contract tests; k6 smoke |
| P2 | U5, U6, U8; T3, T4, T5, T6; RSVP race script |
| P3 | U9–U11; T7, T8 |
| P4 | U7; T10, T11, T12; WS protocol tests |
| P5 | E1–E5; Lighthouse budget |
| P6 | L1–L6; C1–C4; security checks |

---

## 3. Unit Test Priorities

| ID | Target |
|---|---|
| U1 | H3 resolution-7 cell for known coordinates; cache key bucketing (radius 2/5/10/20/50) |
| U2 | Cursor encode/decode; filter fingerprint mismatch rejected |
| U3 | Recurrence: weekly multi-day, biweekly, monthly; Feb 29; DST spring-forward and fall-back in `Europe/Berlin`; `until` inclusive |
| U4 | Authorization matrix: each role × endpoint returns allow/deny |
| U5 | Group owner promotion and last-member archival |
| U6 | RSVP delta computation (GOING/MAYBE/DECLINED transitions) |
| U7 | Message sequence allocation and `client_msg_id` dedupe |
| U8 | Reminder idempotency key stability |
| U9 | Jaccard, availability overlap ratio, distance normalization, repeat penalty, clamp |
| U10 | Greedy formation respects size, centroid and repeat constraints |
| U11 | Match job state transitions (QUEUED→RUNNING→DONE/FAILED) and timeout |
| U12 | Validation limits and error envelope mapping |

---

## 4. Integration Scenarios (critical)

### T1 — Discovery cache miss → PostGIS → cache write
- Empty Redis, seeded PostGIS; `GET /v1/activities?lat&lng&radius_km=10`.
- Assert: one SQL query, Redis key created, second call returns `X-Cache: HIT`,
  identical items and ordering, `distance_m` within ±1 m of `ST_Distance`.

### T2 — Discovery cache key isolation
- Same coordinates, category `sport` then `music`; assert two distinct keys and no leakage.
- Radius 3 km then 10 km must use different buckets (5 and 10).

### T3 — Occurrence idempotency
- Create a series; run the generator twice; assert occurrence count unchanged and
  `UNIQUE (series_id, start_time)` holds.

### T4 — RSVP capacity race
- Capacity 10, confirmed 9; 10 concurrent GOING requests; assert exactly one 200,
  nine `409 EVENT_FULL`, `confirmed_count = 10`, and no negative/over-capacity value.

### T5 — RSVP transitions
- GOING → DECLINED → GOING with capacity 1: second and third calls succeed,
  count returns to 1; MAYBE never consumes capacity; RSVP after `start_time` → 409.

### T6 — Membership capacity race
- `max_members = 6`, 5 ACTIVE; 10 concurrent joins; exactly one succeeds, the rest
  `409 GROUP_FULL`; ACTIVE count = 6.

### T7 — Matching repeat-exposure constraint
- Two users with 2 shared confirmed groups in 90 days are never in one proposal.
- Candidate cap: 600 eligible users → job evaluates ≤ 500 and completes < 5 min.

### T8 — Invitation lifecycle
- 4 invitations → 4 accepts → one group with 4 ACTIVE members and
  `match_groups.group_id` set. Decline before quorum → proposal EXPIRED and a new
  job exists for remaining acceptors. Accept after `expires_at` → `409 INVITATION_EXPIRED`.

### T9 — Auth lifecycle
- Register → login → refresh rotates cookie → old refresh returns 401 and revokes
  the family → logout invalidates. Expired access token → 401 `UNAUTHENTICATED`.

### T10 — Message sequence and dedupe
- 50 concurrent sends in one group: sequence ids unique and contiguous at the end;
  duplicate `client_msg_id` produces one row; history returns newest-first.

### T11 — Cross-instance fan-out
- Start two Spring contexts (distinct ports) against one Redis/DB; client A on
  instance 1 sends, client B on instance 2 receives within 150 ms; no duplicates.

### T12 — Redis outage behaviour
- Stop Redis: discovery still 200 (PostGIS), auth refresh → 503, WS ticket → 503,
  rate limiting fails open with a warning; restart Redis, no stale lock left behind.

---

## 5. E2E Flows (Playwright, 390 × 844)

| ID | Flow |
|---|---|
| E1 | Register → complete profile (location, 3 interests) → discovery shows nearby cards |
| E2 | Open card → join group → see next occurrence |
| E3 | Create a weekly series as owner → RSVP GOING → see confirmed count update |
| E4 | Send a chat message → appears for a second member in the same thread |
| E5 | Request match → accept invitation → new group appears in "My groups" |

Rules: one seeded city fixture; tests create unique emails; no test depends on
execution order; each run starts from a migrated, seeded database.

---

## 6. Contract Testing

- springdoc generates `/v3/api-docs` from annotations; CI fails if generation errors.
- MockMvc contract tests assert status, error `code`, and required fields for every
  endpoint in `lld.md` §3.
- No Pact/consumer tests in the MVP (no external consumers yet).

---

## 7. Load Testing (k6)

| ID | Profile | VUs / duration | Success criteria |
|---|---|---|---|
| L1 | Smoke | 5 / 2 min | 0 % errors, p95 < 300 ms |
| L2 | Discovery stress | ramp 0→500 / 8 min | p95 < 200 ms hit, errors < 0.5 % |
| L3 | RSVP spike | 10 concurrent / 1 iteration | exactly 1 success, 9 conflicts, 0 × 5xx |
| L4 | Messaging fan-out | 1,000 WS / 5 min | delivery p95 < 150 ms, 0 dropped |
| L5 | Soak | 300 / 2 h | heap growth < 5 %, stable p95, cache hit > 85 % |
| L6 | Peak | 0→1,000 / 15 min | p95 < 400 ms, recovery < 60 s |

### 7.1 Discovery script (baseline)

~~~javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const cacheHitRate = new Rate('cache_hit_rate');

export const options = {
  scenarios: {
    discovery: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 200 },
        { duration: '5m', target: 500 },
        { duration: '3m', target: 500 },
        { duration: '2m', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<200'],
    http_req_failed: ['rate<0.005'],
    cache_hit_rate: ['rate>0.85'],
  },
};

export default function () {
  const lat = 52.5200 + (Math.random() - 0.5) * 0.1;
  const lng = 13.4050 + (Math.random() - 0.5) * 0.1;
  const res = http.get(
    `${__ENV.BASE_URL}/v1/activities?lat=${lat}&lng=${lng}&radius_km=10`,
    { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } }
  );
  cacheHitRate.add(res.headers['X-Cache'] === 'HIT');
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(1);
}
~~~

The P1 smoke script (`docs/k6_discovery_smoke.js`) registers one user per VU so
each token stays under the documented 120 requests/minute discovery limit. Load
profiles that reuse one token must account for that limit or run against a load
environment with rate limiting explicitly disabled.

The capacity race script is `rsvp_race_script.js` (L3); run it with
`k6 run -e BASE_URL=... -e EVENT_ID=... -e TOKEN=... docs/rsvp_race_script.js`.

---

## 8. Chaos / Resilience

| ID | Test | Criterion |
|---|---|---|
| C1 | Redis stopped mid-discovery load | Discovery 200 via PostGIS, error rate < 1 %, recovery < 60 s |
| C2 | API process killed during load | Single-instance MVP: restart within 30 s, no corrupt RSVP counts |
| C3 | WebSocket force-closed | Client reconnects with backoff < 30 s; outbox flushes exactly once |
| C4 | Database restart | `/readyz` 503 while down, 200 after; no partial writes |

---

## 9. Test Data and Isolation

- Testcontainers: `postgis/postgis:16-3.4` and `redis:7-alpine`, one container per
  JVM, schema migrated by Flyway, truncated between tests.
- Deterministic seed fixture: 1 city (Berlin), 3 categories, 20 activities,
  4 groups, 2 series, 10 users with known coordinates.
- E2E uses the same fixture through a dedicated `test` profile; never run E2E
  against a database with real user data.
- Time is injected via `Clock`; no test depends on wall-clock time or sleeps
  longer than 200 ms.

---

## 10. Execution

**CI (GitHub Actions, Docker available)**

~~~bash
cd api && mvn -B verify            # unit + integration (Failsafe)
cd web && npm ci && npm run lint && npm run build
npx playwright test                # against composed stack
k6 run docs/rsvp_race_script.js    # nightly
~~~

**Local (current machine: no Docker, no PostGIS, no Redis)**

Integration tests cannot run until PostGIS and Redis exist (see
`implementation_plan.md` §6). Once installed, either:

1. Start `infra/docker-compose.yml` and use Testcontainers normally (requires Docker), or
2. Use the `local-it` Maven profile: `-Dspring.profiles.active=local-it` with
   `KINSHIP_TEST_DB_URL` and `KINSHIP_TEST_REDIS_URL` pointing at a dedicated
   `kinship_test` database. CI keeps using Testcontainers; do not let the two paths
   diverge in schema (both run Flyway).

Report format for each run: timestamp, commit, suite, pass/fail counts, p95 and
cache-hit numbers for load tests. Store baselines in `docs/perf/baseline.md`.

---

## 11. Quality Gates

- No merge with failing unit or integration tests.
- Coverage: ≥ 80 % for `event`, `match`, `activity`; ≥ 60 % overall.
- Every bug fix adds a regression test.
- Security: no high-severity dependency findings; auth matrix tests green.
- Performance: Phase 6 baseline must not regress by > 20 % on later changes.
