# Phase 2 — Groups & Recurring Events

Date: 2026-11-04
Status: **P2 COMPLETE — acceptance PASS.** P3 not started at the time of this checkpoint.

---

## 1. Implemented scope

- Group creation, detail, update, owner approval and leave.
- Public/private membership, join/leave, pending approval, capacity enforcement.
- Owner promotion on leave; last-owner archival.
- Group listing for the current user (`mine=true`) with keyset pagination.
- Recurring series creation (WEEKLY, BIWEEKLY, MONTHLY), 8-week occurrence generation.
- Occurrence listing and event detail.
- RSVP `GOING`, `NOT_GOING` (plus the documented `MAYBE`/`DECLINED`) with
  capacity enforced on GOING only.
- Daily occurrence-extension scheduler and reminder log stub.
- Future RSVPs are cancelled when a member leaves or is removed.

## 2. Migration

`V3__rsvp_not_going.sql` extends the RSVP status check to accept `NOT_GOING`
alongside `GOING`, `MAYBE`, and `DECLINED`. No existing migration was edited.

## 3. API

| Method | Path | Behaviour |
|---|---|---|
| GET | `/v1/groups?mine=true` | keyset-paginated memberships |
| POST | `/v1/groups` | create group, owner membership, 201 |
| GET | `/v1/groups/{id}` | group, members, next 5 events |
| PATCH | `/v1/groups/{id}` | owner update |
| POST | `/v1/groups/{id}/join` | ACTIVE or PENDING |
| DELETE | `/v1/groups/{id}/members/me` | leave, promote/archive |
| PATCH | `/v1/groups/{id}/members/{userId}` | approve/remove |
| POST | `/v1/groups/{id}/events` | create series + generate occurrences |
| GET | `/v1/groups/{id}/events` | schedule with `my_status` |
| GET | `/v1/events/{id}` | detail + RSVP counts |
| POST | `/v1/events/{id}/rsvp` | transactionally enforce capacity |
| PATCH | `/v1/event-series/{id}` | cancel future occurrences |

## 4. Live acceptance

A live script exercised the running API against `kinship_acceptance`:
**32 checks, 32 PASS**.

Included: create/join/full, detail, series generation (8 occurrences),
duplicate series 409, event detail, GOING/idempotent/NOT_GOING transitions,
capacity fill, non-member 403, RSVP counts, concurrent capacity race
(exactly one 200 + one 409), private join/approval, my-groups, owner
promotion, last-member archival, series cancellation, RSVP after cancellation 409.

## 5. Automated test evidence

| Suite | Result |
|---|---|
| Unit tests | **16 run, 0 failures** (11 P1 + 5 recurrence) |
| `GroupsEventsIT` | **4 run, 0 failures** |
| P1 regression ITs | `DiscoveryIT` 2, `AuthFlowIT` 1 — all pass |
| `mvn -q verify` | **PASS** |
| Recurrence unit cases | weekly, Feb-29 skip (non-leap year), DST spring-forward, invalid interval/byday |
| Concurrency | join race and RSVP race both asserted exactly one success |
| Idempotency | generator rerun inserts 0 duplicates |
| Leave | future GOING RSVP becomes DECLINED and `confirmed_count` decrements |

## 6. Decisions

1. `NOT_GOING` is accepted and stored as its own value, per the P2 request;
   existing `MAYBE`/`DECLINED` remain valid for compatibility. Only `GOING`
   consumes capacity.
2. Group capacity is enforced by locking the group row and counting ACTIVE
   members in the same transaction.
3. RSVP capacity is enforced by `SELECT ... FOR UPDATE` on the occurrence.
4. Occurrence generation regenerates from `starts_on` and relies on
   `UNIQUE (series_id, start_time)` for idempotency; `generated_through` is
   updated after the batch.
5. Cancelling a series marks future occurrences CANCELLED and retains RSVP rows
   for audit, matching the LLD.
6. `mine=true` is required for the group list, matching the LLD.

## 7. Deferred / risks

- No P2 web screens were added; the frontend remains the P1 discovery/auth
  shell. UI work is assigned to P5 in the delivery plan.
- Reminder delivery is a log stub by design; only the idempotency key is written.
- Series editing (beyond cancellation) remains out of MVP scope.
- Future RSVP cancellation locks occurrences in ascending id order to avoid
  deadlocks; covered by the leave integration test.

P2 acceptance passed; proceeding to P3.
