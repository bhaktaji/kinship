# Low-Level Design (LLD)
## Recurring-Activity Friendship Platform

Version: 1.1 | Status: Reviewed for implementation (Phase 0) | Backend: Java 21 / Spring Boot 3.3

This document is the **API and behaviour contract**. Implement against it; do not
invent endpoints or fields. Schema details live in `erd.md`, index DDL in
`indexes.sql`, phase order in `implementation_plan.md`.

---

## 0. Conventions

| Concern | Rule |
|---|---|
| Base path | `/v1` (health and OpenAPI are unversioned) |
| Content | JSON, UTF-8, `snake_case` field names |
| IDs | UUID v4 strings, except `interests.id` (int) |
| Timestamps | RFC 3339 UTC with `Z` (e.g. `2026-11-05T13:00:00Z`); local wall times are `HH:mm` or `YYYY-MM-DD` plus IANA zone |
| Auth | `Authorization: Bearer <access_token>`; 15-minute access JWT |
| Refresh | Opaque token in httpOnly cookie `kinship_rt`, `Path=/v1/auth`, `SameSite=Lax`, `Secure` outside localhost |
| Errors | `{ "error": { "code", "message", "details", "trace_id" } }` |
| Pagination | Keyset only. Discovery uses opaque `page_token`; messages use `before_seq` |
| Compression | Brotli/gzip at the edge or Spring compression for responses > 1 KB |
| Tracing | Every response carries `X-Request-Id`; the value also appears as `trace_id` in errors |

### 0.1 Idempotency

There is **no generic Idempotency-Key middleware in the MVP**. Duplicate
protection is per endpoint, using natural keys:

| Operation | Guard |
|---|---|
| RSVP | PK `(event_id, user_id)`; repeating the same status is a 200 no-op |
| Occurrence generation | `UNIQUE (series_id, start_time)` + `ON CONFLICT DO NOTHING` |
| Message send | `UNIQUE (group_id, client_msg_id)`; duplicate returns the stored message with 200 |
| Match request | Partial unique index: one `QUEUED`/`RUNNING` job per user |
| Series creation | `UNIQUE (group_id, title, starts_on)`; duplicate returns 409 with the existing `event_series_id` |
| Group join | PK `(group_id, user_id)`; repeat join returns current status |
| Refresh rotation | Token id is single-use; reuse revokes the token family |

### 0.2 Browser transport (CORS, cookies, CSRF)

- API and web are separate origins in development (`localhost:8080` / `localhost:3000`).
  CORS allowlist comes from `KINSHIP_CORS_ORIGINS` (comma-separated); credentials
  are enabled; allowed headers are `Authorization, Content-Type, If-None-Match`;
  exposed headers are `ETag, X-Cache, X-Request-Id, Retry-After`.
- The refresh cookie is `HttpOnly; SameSite=Lax; Path=/v1/auth` and `Secure` outside
  localhost. The access token is kept in memory only (never `localStorage`).
- No CSRF token is required because state-changing endpoints authenticate with the
  `Authorization` header and the only cookie endpoint (`/auth/refresh`) accepts no
  body and is protected by `SameSite=Lax`. Do not add cookie-authenticated write
  endpoints without adding CSRF protection.

### 0.3 Rate limits (Redis fixed-window counters)

| Scope | Limit |
|---|---|
| Login / register per IP+email | 5 per minute, 20 per hour |
| Refresh per user | 30 per minute |
| Discovery per user | 120 per minute |
| RSVP per user | 20 per minute |
| Message send per user | 30 per minute |
| Match request per user | 3 per day |

Exceeded → `429` with `Retry-After` seconds.

### 0.4 Validation limits

| Field | Rule |
|---|---|
| email | RFC 5322 practical subset, max 254 chars, lower-cased |
| password | 10–72 chars (BCrypt limit), must contain a letter and a digit |
| display_name | 2–50 chars, trimmed |
| group name | 3–80 chars |
| description / message body | 1–2000 chars |
| max_members | 2–20 (default 6) |
| duration_min | 30–480 (default 90) |
| capacity | 1–100 (default group.max_members) |
| radius_km | 1–50 (default 10) |
| limit | 1–50 (default 20); events/messages max 100/50 |

---

## 1. Authentication and Sessions

### 1.1 Endpoints

**POST /v1/auth/register**

~~~json
{ "email": "ana@example.com", "password": "s3cret-pass", "display_name": "Ana", "timezone": "Europe/Berlin" }
~~~

201 response body:

~~~json
{ "user": { "id": "…", "email": "ana@example.com", "display_name": "Ana", "timezone": "Europe/Berlin" },
  "access_token": "…", "token_type": "Bearer", "expires_in": 900 }
~~~

Also sets the `kinship_rt` cookie. Errors: `409 EMAIL_TAKEN`, `400 VALIDATION_ERROR`.

**POST /v1/auth/login** — `{email, password}` → same 200 body/cookie as register.
`401 INVALID_CREDENTIALS` (identical message for unknown email and wrong
password; constant-time compare).

**POST /v1/auth/refresh** — no body; reads cookie → 200 `{access_token, token_type, expires_in}`
and a rotated cookie. `401 INVALID_REFRESH` if missing, expired, or reused.

**POST /v1/auth/logout** — 204, revokes the presented refresh token. Idempotent.

### 1.2 Token model

- Access token: JWT HS256, claims `sub` (user id), `jti`, `iss="kinship"`, `iat`, `exp`.
  Secret from `KINSHIP_JWT_SECRET` (≥ 32 bytes). Validated for signature, `exp`, `iss`.
- Refresh token: `rt_<userId>_<tokenId>_<secret>`, 256-bit secure random. Only
  `SHA-256(secret)` is stored in Redis under `rt:{userId}:{tokenId}` with 30-day TTL;
  `rt_index:{userId}` tracks token ids for family revocation. Refresh rotates: old
  key deleted, new key written. Presenting a rotated token revokes every refresh
  token for that user (reuse detection).
- Passwords: BCrypt cost 12. Never logged.
- `GET /v1/users/me` includes the user's `timezone`, defaulting to `UTC` when absent.

Email verification and password reset are **out of MVP scope** (`email_verified_at`
stays null; no mailer is configured). This is a conscious scope cut recorded in
`hld.md` §12.

---

## 2. Authorization Matrix

| Endpoint group | Anonymous | Authenticated | ACTIVE group member | Group OWNER |
|---|---|---|---|---|
| `POST /auth/*`, `/healthz`, `/readyz`, `/v3/api-docs` | yes | yes | — | — |
| `GET /activities*`, `GET /interests` | no | yes | — | — |
| `GET/PATCH /users/me` | no | own record | — | — |
| `POST /groups` | no | yes | — | — |
| `GET /groups?mine=true` | no | own memberships | own memberships | own memberships |
| `GET /groups/{id}` | no | public groups | private groups | private groups |
| `PATCH /groups/{id}`, `PATCH .../members/{userId}` | no | no | no | yes |
| `POST /groups/{id}/join`, `DELETE .../members/me` | no | yes | idempotent | see §3.3 |
| `POST /groups/{id}/events`, `PATCH /event-series/{id}` | no | no | no | yes |
| `GET /groups/{id}/events`, `GET /events/{id}` | no | public groups | all | all |
| `POST /events/{id}/rsvp` | no | no | yes | yes |
| `POST /match-requests`, `GET /match-invitations` | no | own records | — | — |
| `POST /match-invitations/{id}/*` | no | invitee only | — | — |
| `GET/POST /groups/{id}/messages`, `POST /realtime/tickets` | no | no | yes | yes |

Enforcement: a Spring MVC interceptor resolves the JWT into a `UserPrincipal`;
service methods call an `AuthorizationService` that loads membership state
(`@Cacheable` is **not** used for authorization decisions). Unknown or
non-visible resources return `404` rather than `403` to avoid leaking existence;
`403` is used only when the caller can already see the resource.

---

## 3. API Specification

### 3.1 Users

**GET /v1/users/me** → 200

~~~json
{ "id": "…", "email": "ana@example.com", "display_name": "Ana", "timezone": "Europe/Berlin",
  "location": { "lat": 52.52, "lng": 13.405 },
  "availability": [{ "day_of_week": 4, "start_local": "18:00", "end_local": "21:00" }],
  "interests": [{ "interest_id": 3, "slug": "bouldering", "weight": 1.0 }],
  "created_at": "2026-11-01T10:00:00Z" }
~~~

**PATCH /v1/users/me** — all fields optional:
`{display_name?, timezone?, location?{lat,lng}, availability?, interests?[{interest_id, weight?}]}`

- `interests` replaces the full set (transactional delete+insert). Weight defaults to 1.0, range 0.1–5.0.
- `availability` replaces the full weekly slot set (max 14 slots, `start_local < end_local`,
  ISO weekday 1–7). It is the **only** source of availability for matching.
- Setting `location` recomputes `h3_cell_r7` (H3 resolution 7) in the same transaction.
- 200 returns the same shape as GET. `400 VALIDATION_ERROR` for invalid IANA zone or coordinates.

**GET /v1/interests** → `{ "items": [{ "id", "slug", "name", "category" }] }` (taxonomy, no pagination; < 200 rows).

### 3.2 Activity discovery

**GET /v1/activities**

| Param | Required | Rule |
|---|---|---|
| lat, lng | yes | -90..90, -180..180 |
| radius_km | no | 1–50, default 10 |
| category | no | taxonomy slug; unknown → 400 |
| starts_after | no | ISO instant; only activities with a next occurrence at/after it |
| group_size_max | no | 2..20; only groups whose `max_members` ≤ value |
| page_token | no | opaque cursor from a previous response |
| limit | no | 1..50, default 20 |

Response 200:

~~~json
{ "items": [ {
    "activity_id": "…", "title": "Thursday Bouldering", "category": "sport",
    "description": "…", "image_url": "https://…",
    "location": { "lat": 52.51, "lng": 13.40, "address": "…" },
    "distance_m": 812,
    "group_id": "…", "group_name": "Thursday Bouldering Crew", "member_count": 5, "max_members": 6,
    "next_event": { "event_id": "…", "start_time": "2026-11-05T18:30:00Z" }
  } ],
  "next_page_token": null }
~~~

Behaviour:

1. Resolve authenticated user (401 if absent).
2. `cell = h3(lat, lng, 7)`; `radius_bucket` ∈ {2,5,10,20,50} = smallest bucket ≥ radius.
3. Redis key `geo:{cell}:{category|all}:{radius_bucket}`. Value: JSON array of
   cards (max 200, ordered by `distance_m, activity_id`). Hit → continue at 5.
4. Miss → one SQL statement: bounding box on `activities.location` (expanded by
   the **bucket** radius) AND `ST_DWithin(location, point, bucket_radius_m)`, join
   the group that has capacity and the earliest future occurrence, lateral join
   `next_event` and active `member_count`. `LIMIT 200`. Write to Redis with TTL 120 s.
5. In-memory filter by the requested radius (≤ bucket), `starts_after`,
   `group_size_max`, then paginate.
6. If a card has no upcoming occurrence it is excluded (no dead cards).

`page_token` encodes `{v:1, f:<fingerprint of lat/lng/radius/category/starts_after/group_size_max>, d:<distance_m>, id:<activity_id>}`
Base64URL. Server recomputes the fingerprint and returns `400 INVALID_CURSOR`
when filters changed. `next_page_token` is null when fewer than `limit` items remain.

Every successful response sets `X-Cache: HIT|MISS` (used by k6 and tests) and a
strong `ETag`; `If-None-Match` with the same value returns `304` with no body.
Cache staleness is bounded by the 120 s TTL; there is no synchronous
invalidation in the MVP (documented trade-off in `hld.md` §6).

**GET /v1/activities/{id}** → activity fields above plus `organization` (nullable)
and `groups[]` (public, ACTIVE, capacity available) with the card fields.

### 3.3 Groups and membership

**POST /v1/groups**

~~~json
{ "name": "Thursday Bouldering Crew", "description": "Weekly bouldering, all levels",
  "category": "sport", "activity_id": "…", "location": { "lat": 52.51, "lng": 13.40 },
  "max_members": 6, "visibility": "public" }
~~~

- `location` optional when `activity_id` is supplied (inherited from the activity);
  otherwise required → `400 LOCATION_REQUIRED`.
- Creator is inserted as `OWNER, ACTIVE` in the same transaction. 201 returns the
  full group object.

**GET /v1/groups?mine=true&limit=20&page_token=** → non-archived groups where the caller is
ACTIVE or PENDING (`{ "items": [group card], "next_page_token" }`, newest
membership first, keyset on `(joined_at DESC, group_id)`). Used by "My groups";
`mine` is required for this list in the MVP.

**GET /v1/groups/{id}** → `{ "group": {...}, "members": [...], "next_events": [...] }`
with `members` limited to ACTIVE rows (≤ 20 by `max_members`; the owner also sees
PENDING requests) and `next_events` = next 5 `SCHEDULED` occurrences ordered by
`start_time`. Private groups return 404 to non-members.

**PATCH /v1/groups/{id}** — owner only. `{name?, description?, max_members?, visibility?}`.
`409 GROUP_SIZE_BELOW_MEMBERS` when `max_members` < current ACTIVE count.

**POST /v1/groups/{id}/join** → 200 `{ "group_id", "status": "ACTIVE" | "PENDING" }`

- Public + capacity available → ACTIVE (reactivates a `LEFT` row instead of inserting).
- Public + full → `409 GROUP_FULL`.
- Private → `PENDING` (owner approves); duplicate calls return the current status.
- Archived group → `409 GROUP_ARCHIVED`.
- Capacity check and insert happen in one transaction with the group row locked
  (`SELECT ... FOR UPDATE`), so concurrent joins cannot exceed `max_members`.

**DELETE /v1/groups/{id}/members/me** → 204

- A PENDING member cancels their request: the row is set to `LEFT` and 204 returned.
- Sets membership `LEFT`, and in the same transaction marks the user's future
  `GOING` RSVPs as `DECLINED`, decrementing `confirmed_count`.
- Owner leaving: if another ACTIVE member exists, the longest-tenured member is
  promoted to OWNER; otherwise the group becomes `ARCHIVED` and all memberships
  become `LEFT`. No owner-less ACTIVE group may exist.
- When cancelling future RSVPs, lock affected occurrence rows in ascending `id`
  order to avoid deadlocks with concurrent RSVP transactions.

**PATCH /v1/groups/{id}/members/{user_id}** — owner only:
`{ "status": "ACTIVE" | "REMOVED" }`. ACTIVE approves a PENDING member (capacity
checked); REMOVED removes a member (not the owner, not the caller).

### 3.4 Recurring event series and occurrences

**POST /v1/groups/{id}/events** (owner)

~~~json
{ "title": "Bouldering night", "duration_min": 90, "capacity": 6, "timezone": "Europe/Berlin",
  "recurrence": { "freq": "WEEKLY", "interval": 1, "byday": ["THU"],
                  "start_time": "18:30", "starts_on": "2026-11-05", "until": "2027-03-01" } }
~~~

- `freq` ∈ `WEEKLY | BIWEEKLY | MONTHLY`; `interval` must be 1
  (`400 UNSUPPORTED_RECURRENCE` otherwise).
- `byday` required for WEEKLY/BIWEEKLY (ISO weekday names `MON..SUN`, max 3,
  unique); omitted for MONTHLY (day-of-month comes from `starts_on`).
- `starts_on` must be today or later and within 8 weeks. `until` optional, ≥ `starts_on`.
- `timezone` optional; defaults to the owner's zone, then `UTC`. It is stored on the series.
- `capacity` defaults to `groups.max_members`; must be ≥ current `GOING` count.

201 response: `{ "event_series_id": "…", "generated_through": "…", "occurrences": [ { "event_id", "start_time", "end_time" } ] }`
generated for the next 8 weeks (see §4). Duplicate series creation →
`409 SERIES_EXISTS` with `details.existing_series_id`.

**GET /v1/groups/{id}/events?from=&to=&limit=** — default `from=now`, `to=now+8w`,
max range 16 weeks, `limit` ≤ 100. Returns `{items: [{event_id, series_id, title,
start_time, end_time, capacity, confirmed_count, status, my_status}]}` ordered by
`start_time`. Members see all occurrences; non-members of a public group see
`SCHEDULED` ones only.

**GET /v1/events/{id}** → event detail plus `rsvp_counts {going, maybe, declined, not_going}`,
`my_status` (null when none), and `group {id, name, visibility}`.

**PATCH /v1/event-series/{id}** (owner) — `{ "status": "CANCELLED" }` is the only
supported mutation in the MVP. Cancels future occurrences (`status='CANCELLED'`)
and returns `{ "cancelled_occurrences": n }`. Existing RSVPs are retained for
audit but no longer count toward capacity; clients hide cancelled events.

### 3.5 RSVP

**POST /v1/events/{id}/rsvp** — `{ "status": "GOING" | "NOT_GOING" | "MAYBE" | "DECLINED" }`

- Requires ACTIVE membership of the owning group.
- Only `GOING` consumes capacity. `NOT_GOING`, `MAYBE` and `DECLINED` do not.
  `NOT_GOING` is the P2 status; `MAYBE`/`DECLINED` remain accepted for compatibility.
- 200 → `{ "event_id", "status", "confirmed_count", "capacity" }`
- Errors: `409 EVENT_FULL`, `409 EVENT_CANCELLED`, `409 EVENT_STARTED`.
- Idempotent: when the stored status already equals the requested status, the
  service returns the current counts **without executing the write block** (no
  version bump, no count change).

Transaction (mandatory, READ COMMITTED):

~~~sql
BEGIN;
SELECT status, capacity, confirmed_count, start_time
  FROM events WHERE id = :eventId FOR UPDATE;
-- reject if status <> 'SCHEDULED' or start_time <= now()

SELECT status FROM rsvps WHERE event_id = :eventId AND user_id = :userId;

-- delta = (new = 'GOING' ? 1 : 0) - (old = 'GOING' ? 1 : 0)
-- if delta > 0 and confirmed_count + delta > capacity -> 409 EVENT_FULL

INSERT INTO rsvps (event_id, user_id, status, version, responded_at, updated_at)
VALUES (:eventId, :userId, :status, 1, now(), now())
ON CONFLICT (event_id, user_id) DO UPDATE
  SET status = EXCLUDED.status,
      version = rsvps.version + 1,
      responded_at = now(),
      updated_at = now();

UPDATE events
   SET confirmed_count = confirmed_count + :delta,
       version = version + 1,
       updated_at = now()
 WHERE id = :eventId AND :delta <> 0;
COMMIT;
~~~

`confirmed_count` is authoritative for capacity and is never recomputed on the
read path. A nightly consistency check (Phase 6) compares it with
`count(rsvps WHERE status='GOING')` and logs drift.

### 3.6 Async matching

**POST /v1/match-requests**

~~~json
{ "max_distance_km": 10, "preferred_group_size": 4 }
~~~

- Interests and availability come from the caller's **profile**; the request does
  not repeat them. The job snapshot in `match_jobs.preferences` records the values
  used, so later profile edits do not change a queued job.
- Requires a complete profile: `location` set, ≥ 2 interests, and ≥ 1 availability
  slot → otherwise `422 PROFILE_INCOMPLETE`. Candidates without availability are excluded.
- If a `QUEUED`/`RUNNING` job already exists for the user, return it (202) instead
  of creating a second job.
- 202 → `{ "job_id": "…", "status": "QUEUED" }`.

**GET /v1/match-requests/{job_id}** (owner) →

~~~json
{ "job_id": "…", "status": "DONE",
  "results": [ { "match_group_id": "…", "status": "INVITED", "score": 0.78,
                 "expires_at": "2026-11-05T12:00:00Z",
                 "members": [ { "user_id": "…", "display_name": "Ana",
                                "interests": ["bouldering"], "distance_km": 2.1 } ] } ] }
~~~

`FAILED` jobs include `error_code` (`NO_CANDIDATES`, `JOB_TIMEOUT`, `INTERNAL`).
Emails are never exposed in match payloads.

**GET /v1/match-invitations?status=INVITED** → `{items:[{invitation_id, match_group_id,
expires_at, status, members[]}]}` (newest first, no pagination: ≤ 5 active invites).

**POST /v1/match-invitations/{id}/accept** → 200 `{ "status": "ACCEPTED", "group_id": null | "…" }`

- Sets the invitation ACCEPTED. When the number of ACCEPTED invitations reaches
  the group size target (or 4, whichever is lower), the application creates a real
  group (visibility `private`, `max_members` = target size, category/name derived
  from the shared interest), adds accepted users as ACTIVE members, links
  `match_groups.group_id`, sets status `CONFIRMED`, and returns `group_id`.
  The job requester becomes the group OWNER; the group is not created unless the
  requester has accepted, and the proposal expires at `expires_at` otherwise.
- `409 INVITATION_EXPIRED` after `match_groups.expires_at`.
- Accepting twice is idempotent.

**POST /v1/match-invitations/{id}/decline** → 200 `{ "status": "DECLINED" }`.
If the remaining accepted+invited count < 4, the proposal becomes `EXPIRED` and a
fresh job is queued for the users who had accepted. Otherwise the proposal
continues without the decliner.

### 3.7 Messaging

**POST /v1/realtime/tickets** — `{ "group_id": "…" }`, ACTIVE member only →
201 `{ "ticket": "…", "expires_in": 60 }`. Stored in Redis as
`ws_ticket:{sha256(ticket)}` → `{userId, groupId}`, single use, 60 s TTL.

**WS /v1/ws?ticket=…** — protocol in §7.

**POST /v1/groups/{id}/messages** — `{ "body": "…", "client_msg_id": "uuid" }` →
201 `{ "message_id", "sequence_id", "created_at" }`; duplicate `client_msg_id`
returns 200 with the stored message. 403 when not an ACTIVE member; 400 for
empty/oversized body.

**GET /v1/groups/{id}/messages?before_seq=&limit=50** — newest first, ACTIVE
members only:

~~~json
{ "items": [ { "message_id": "…", "sequence_id": 42, "sender_id": "…",
               "body": "…", "created_at": "…" } ],
  "next_before_seq": 41 }
~~~

`next_before_seq` is null when no older messages exist. `before_seq` omitted =
latest page.

### 3.8 Operational endpoints

| Endpoint | Behaviour |
|---|---|
| `GET /healthz` | 200 `{"status":"ok"}`; no dependency checks |
| `GET /readyz` | 200 when DB and Redis respond; 503 with `{"db":bool,"redis":bool}` otherwise |
| `GET /v3/api-docs` | springdoc OpenAPI 3.0 JSON (Swagger UI at `/swagger-ui.html`) |

---

## 4. Recurrence Engine

Rule JSON stored in `event_series.recurrence_rule`:

~~~json
{ "freq": "WEEKLY", "interval": 1, "byday": ["THU"], "start_time": "18:30" }
~~~

Generation algorithm (service, not DB):

1. Resolve the series zone (`event_series.timezone`).
2. Compute the first occurrence: `ZonedDateTime.of(starts_on, LocalTime.parse(start_time), zone)`.
   Java resolves DST gaps by shifting forward by the gap and overlaps by using
   the earlier offset; this behaviour is accepted and covered by tests.
3. Iterate `freq`: WEEKLY +7d, BIWEEKLY +14d, MONTHLY same day-of-month next month.
   For MONTHLY, if the target month has no such day (e.g. 31st, or Feb 29), **skip
   that month** (no clamping) and continue. Stop at `until` (inclusive) or the
   horizon (`now + 8 weeks`), whichever comes first.
4. Insert occurrences in batches with
   `INSERT ... ON CONFLICT (series_id, start_time) DO NOTHING`, `end_time = start_time + duration_min`.
5. Update `event_series.generated_through` to the horizon **after** a successful
   batch commit. A crash before the update re-runs harmlessly thanks to the
   unique constraint.

Daily extension job (Spring `@Scheduled(cron = "0 15 2 * * *")`, UTC): select up
to 200 ACTIVE series with `generated_through < now + 8 weeks`, extend each in its
own transaction, log counts. Series cancelled before the job are skipped.

Occurrences inherit `title`, `capacity`, `duration`, and `location` from the series.
Only `status` and `until` can change after creation in the MVP; editing recurrence
creates a new series (documented limitation).

Reminders: at `now + 24h`, the reminder job logs one line per `GOING` RSVP with
idempotency key `reminder:{event_id}:{user_id}` in Redis (24 h TTL). No email or
push delivery in the MVP.

---

## 5. Matching Algorithm

### 5.1 Candidates

- `cells = k_ring(h3(user.location, 7), 2)` (7 cells); query `users` with
  `h3_cell_r7 = ANY(cells)`, `status='ACTIVE'`, `id <> :userId`, location within
  `max_distance_km`. Order by distance, **cap 500**.
- Keep candidates with `|interests ∩|` ≥ 2 and at least one availability overlap
  of ≥ 30 minutes on the same ISO weekday.
- Exclude users already in a group with the requester.

### 5.2 Score (0..1, clamped)

~~~text
score = 0.4 * jaccard(interests)
      + 0.3 * availability_overlap_ratio   (overlap minutes / 180, capped at 1)
      + 0.2 * (1 - distance_km / max_distance_km)
      - 0.3 * repeat_penalty               (0.3 per shared confirmed group in 90 days, capped at 1)
~~~

Clamp to `[0, 1]`; persist rounded to 3 decimals.

### 5.3 Group formation (greedy seed-and-extend)

1. Seed = candidate with the highest sum of pairwise scores.
2. Add the candidate maximising the average score against current members.
3. Hard constraints for every addition:
   - pairwise repeat penalty = 0 (no pair shared 2+ confirmed groups in 90 days);
   - centroid of `group ∪ {candidate}` is within `max_distance_km` of the requester;
   - group size ≤ `preferred_group_size` (4–6).
4. Stop when the group reaches `preferred_group_size` or no candidate satisfies the
   constraints. A proposal needs at least 4 members; if fewer than 4 remain, persist
   the job as `FAILED` with `NO_CANDIDATES` and create no invitations.
5. Persist `match_groups` (status `INVITED`, `expires_at = now + 48h`) and one
   `match_invitations` row per member (including the requester).

Complexity is O(N²·k) with N ≤ 500 (≤ 125k pairs), which stays under the 5-minute
batch target.

### 5.4 Worker

- `@Scheduled(fixedDelay = 10_000)` polls with:

~~~sql
UPDATE match_jobs SET status = 'RUNNING', started_at = now()
WHERE id = (SELECT id FROM match_jobs
            WHERE status = 'QUEUED'
            ORDER BY created_at
            FOR UPDATE SKIP LOCKED
            LIMIT 1)
RETURNING *;
~~~

- Per-job timeout 5 minutes → `FAILED` / `JOB_TIMEOUT`.
- Expiry job every 10 minutes: proposals past `expires_at` become `EXPIRED`;
  pending invitations become `EXPIRED`.
- Re-match after decline creates a new job for the remaining accepted users; it
  does not mutate the expired proposal.

---

## 6. Concurrency, Transactions and Consistency

| Concern | Mechanism |
|---|---|
| Overbooking | `SELECT ... FOR UPDATE` on the occurrence, check inside the transaction |
| Group capacity | `SELECT ... FOR UPDATE` on the group; count ACTIVE members inside the transaction |
| Duplicate RSVP | PK `(event_id, user_id)` + upsert |
| Duplicate occurrence | `UNIQUE (series_id, start_time)` + `ON CONFLICT DO NOTHING` |
| Message ordering | Per-group counter allocated in the insert transaction |
| Job claiming | `FOR UPDATE SKIP LOCKED` |
| Refresh reuse | Redis token id is single-use |

**Message send** (single transaction):

~~~sql
BEGIN;
UPDATE groups SET last_message_seq = last_message_seq + 1
 WHERE id = :groupId
 RETURNING last_message_seq;          -- sequence_id

INSERT INTO messages (id, group_id, sender_id, body, sequence_id, client_msg_id, created_at)
VALUES (:id, :groupId, :senderId, :body, :sequenceId, :clientMsgId, now())
ON CONFLICT (group_id, client_msg_id) DO NOTHING
RETURNING id, sequence_id, created_at;
COMMIT;
-- publish to Redis channel group:{groupId} only after commit
~~~

If the `client_msg_id` conflict fires, the transaction rolls back the counter
update and the service returns the existing message. Concurrent transactions may
commit out of sequence order, so readers must order by `sequence_id` and tolerate
transient gaps; the counter is never reset, so ordering survives Redis restarts.

Isolation level: `READ COMMITTED` everywhere. No `SERIALIZABLE` retries.

---

## 7. WebSocket Protocol

- **Endpoint:** `wss://…/v1/ws?ticket=<opaque>`, one group per connection.
- **Handshake:** validate and consume the Redis ticket; load membership; reject
  with close code `4401` (unauthenticated), `4403` (not a member), `4409` (expired ticket).
- **Frames (JSON, text, max 8 KB):**

~~~text
// server -> client on connect
{ "type": "ready", "group_id": "…", "last_sequence_id": 41 }
// client -> server
{ "type": "msg", "body": "see you there", "client_msg_id": "…" }
// server -> sender
{ "type": "ack", "client_msg_id": "…", "message_id": "…", "sequence_id": 42, "created_at": "…" }
// server -> group
{ "type": "msg", "message_id": "…", "sequence_id": 42, "sender_id": "…", "body": "…", "created_at": "…" }
// server -> client
{ "type": "error", "code": "MESSAGE_TOO_LONG", "client_msg_id": "…" }
~~~

- Heartbeat: server sends Ping every 30 s; no Pong within 60 s closes the socket.
- **Fan-out:** publish after commit to Redis channel `group:{groupId}`; every pod
  subscribes to channels of groups with local sessions and writes to those
  sessions, de-duplicating by `message_id`. With a single pod this still works
  (the pod is its own subscriber); no in-process bypass is needed.
- **Reconnect:** exponential backoff 1, 2, 4, 8, 16, 30 s with ±20 % jitter;
  client keeps an outbox keyed by `client_msg_id` and flushes after `ready`;
  client backfills via `GET /messages?before_seq=last_sequence_id+1`.
- **Fallback:** if the socket cannot be established, poll the history endpoint
  every 10 s.
- Token never appears in the WS URL; only the 60-second single-use ticket does.

---

## 8. Caching

| Key | Content | TTL | Failure behaviour |
|---|---|---|---|
| `geo:{cell}:{category|all}:{radius_bucket}` | Ordered discovery cards (max 200) | 120 s | Miss/Redis down → PostGIS |
| `rt:{userId}:{tokenId}` | SHA-256 of refresh secret | 30 d | Redis down → refresh fails 503 (auth fails closed) |
| `ws_ticket:{hash}` | `{userId, groupId}` | 60 s | Redis down → realtime unavailable 503 |
| `rl:{scope}:{bucket}` | Rate-limit counter | window | Redis down → allow (fail open), log warning |

Discovery cache misses must never return stale data older than 120 s, and writes
do not purge the cache. This is acceptable for an MVP where activity/venue data
changes on the order of hours; it is recorded as a known trade-off, not a defect.

---

## 9. Error Catalog

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bean Validation failure; `details.fields` lists offending fields |
| 400 | `INVALID_CURSOR` | Malformed or filter-mismatched `page_token` |
| 400 | `UNSUPPORTED_RECURRENCE` | `interval != 1` or invalid `byday` |
| 400 | `LOCATION_REQUIRED` | Group created without location and without activity |
| 401 | `UNAUTHENTICATED` | Missing/expired access token |
| 401 | `INVALID_CREDENTIALS` | Login failure (generic) |
| 401 | `INVALID_REFRESH` | Refresh cookie missing, expired, or reused |
| 403 | `FORBIDDEN` | Visible resource, insufficient role |
| 404 | `NOT_FOUND` | Unknown id, or resource not visible to caller |
| 409 | `EMAIL_TAKEN` | Registration with existing email |
| 409 | `GROUP_FULL` | Join or approval would exceed `max_members` |
| 409 | `GROUP_ARCHIVED` | Join/RSVP on archived group |
| 409 | `GROUP_SIZE_BELOW_MEMBERS` | `max_members` below ACTIVE count |
| 409 | `EVENT_FULL` | GOING would exceed capacity |
| 409 | `EVENT_CANCELLED` | RSVP on a cancelled occurrence |
| 409 | `EVENT_STARTED` | RSVP after `start_time` |
| 409 | `SERIES_EXISTS` | Duplicate recurring series |
| 409 | `INVITATION_EXPIRED` | Accept/decline after expiry |
| 409 | `INVITATION_NOT_PENDING` | Decline an already accepted/expired invitation |
| 422 | `PROFILE_INCOMPLETE` | Match request without location or ≥ 2 interests |
| 429 | `RATE_LIMITED` | Any rate limit; includes `Retry-After` |
| 500 | `INTERNAL` | Unhandled exception; `trace_id` only |
| 503 | `DEPENDENCY_UNAVAILABLE` | Redis required and unavailable |

---

## 10. Out of Scope for the MVP API

Email verification/reset, push notifications, check-in/attendance, reporting and
blocking, payments, group deletion, recurrence editing, multi-group WebSocket
connections, and search by free text. Adding any of these requires a documented
scope change, not an undocumented endpoint.
