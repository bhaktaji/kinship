# Phase 3 — Matching & Invitations

Date: 2026-11-04
Status: **P3 COMPLETE — acceptance PASS.** P4 not started at this checkpoint.

---

## 1. Implemented scope

- Asynchronous match jobs: create, claim, execute, complete/fail.
- Candidate selection from H3 k-ring 2, 10 km radius (configurable), ACTIVE users,
  excluding existing shared groups and users with an active invitation.
- Interest overlap (≥ 2), availability overlap (≥ 30 minutes), distance and
  repeat-exposure scoring exactly as `docs/lld.md` §5.
- Greedy seed-and-extend group formation, 4–6 members, centroid constraint.
- Proposal persistence and one invitation per member, including the requester.
- Invitation listing, accept, decline, 48-hour expiry and expiry sweep.
- Quorum group creation: 4 accepted invitations, requester OWNER, others ACTIVE,
  private group, remaining invitations expired.
- Decline below quorum expires the proposal and queues replacement jobs for the
  users who had accepted.
- Worker retry safety: one proposal per job via a unique index; stale RUNNING
  jobs are failed as `JOB_TIMEOUT` after 5 minutes.

## 2. Migration

`V4__match_job_idempotency.sql` adds `UNIQUE (job_id)` on `match_groups`.
No applied migration was edited.

## 3. API

| Method | Path | Behaviour |
|---|---|---|
| POST | `/v1/match-requests` | 202, one active job per user |
| GET | `/v1/match-requests/{jobId}` | owner-only status/results |
| GET | `/v1/match-invitations?status=INVITED` | up to 5 invitations, member previews |
| POST | `/v1/match-invitations/{id}/accept` | idempotent; group on quorum |
| POST | `/v1/match-invitations/{id}/decline` | expire/requeue below quorum |

No email or exact address is returned; member previews contain display name,
interest names and distance only.

## 4. Live acceptance

Live API run: **21 checks, 21 PASS**. It covered request/duplicate-job idempotency,
job completion, 4-member proposal, invitation presence, four accepts, group
creation (private, owner, 4 members), and a decline flow.

## 5. Automated test evidence

| Test | Result |
|---|---|
| `MatchingIT` | **6 run, 0 failures** |
| Proposal + quorum group | PASS |
| Eligibility: far user, missing availability, too few interests | PASS — job `FAILED / NO_CANDIDATES` |
| Expired invitation at/after 48 h | PASS — 409 `INVITATION_EXPIRED`, group EXPIRED |
| Concurrent accepts | PASS — one CONFIRMED group, 4 ACTIVE members |
| Worker retry | PASS — one match group after requeue/reprocess |
| Decline below quorum | PASS — proposal EXPIRED, 3 replacement jobs queued |
| `MatchScoringTest` | **4 run, 0 failures** |
| P1 + P2 regression ITs | PASS (`DiscoveryIT` 2, `AuthFlowIT` 1, `GroupsEventsIT` 4) |

## 6. Decisions

1. Quorum is 4 accepted invitations, per LLD. The group is created with the
   accepted members and `max_members` equal to that count; remaining invitations
   are expired to avoid over-capacity races.
2. `UNIQUE (job_id)` makes worker retries idempotent; a retried job produces no
   second proposal or invitation set.
3. The worker polls every 10 seconds with `FOR UPDATE SKIP LOCKED`; a stale
   RUNNING job is failed as `JOB_TIMEOUT` after 5 minutes.
4. Decline below quorum queues a fresh job for each accepted user, preserving the
   original `max_distance_km`/`preferred_group_size` snapshot.
5. `INVITATION_NOT_PENDING` is returned when declining an accepted/expired
   invitation; documented in the LLD error catalog.

## 7. Deferred / risks

- A 5,000-user batch benchmark was not run; the 500-candidate cap bounds work
  per job and the 5-minute target remains a P6 load measurement.

- Matching is O(N²) with a 500-candidate cap; acceptable for a single city.
- Only one proposal per job is generated; richer alternatives are out of scope.
- Invitation delivery is pull-based (`GET /match-invitations`); push/email is
  not in the MVP.
- No P3 web screens were added; UI remains P5.

P3 acceptance passed; proceeding to P4.
