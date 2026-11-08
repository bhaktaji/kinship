# Phase 4 — Messaging & Real-time Delivery

Date: 2026-11-04
Status: **P4 COMPLETE — acceptance PASS.**

---

## 1. Implemented scope

- Group message send and keyset history.
- Per-group sequence allocated in the same database transaction as the insert.
- Idempotent sends via `UNIQUE (group_id, client_msg_id)`.
- Publish-after-commit to Redis channel `group:{groupId}`; Redis is not the
  message source of truth.
- Short-lived, single-use WebSocket tickets bound to a user and group.
- WebSocket `/v1/ws?ticket=...`: ready frame with last sequence, inbound `msg`,
  ack, broadcast, error frames, heartbeat pings and close codes.
- Redis pub/sub fan-out with per-group subscriptions and message-id deduplication.
- Membership authorization for ticket issuance, sending, reading and receiving.
- Reconnect recovery via the `ready` last sequence plus REST history
  (`before_seq`).

## 2. API

| Method | Path | Behaviour |
|---|---|---|
| POST | `/v1/groups/{id}/messages` | 201 create, 200 duplicate client id, 403 non-member |
| GET | `/v1/groups/{id}/messages?before_seq=&limit=` | newest-first keyset page |
| POST | `/v1/realtime/tickets` | 201 ticket, 60 s TTL, single use |
| WS | `/v1/ws?ticket=...` | ready / msg / ack / error |

## 3. Live acceptance

Live API + WebSocket run: **18 checks, 18 PASS**.

- 10 concurrent sends produced contiguous sequence ids 1–10.
- Duplicate `client_msg_id`: 201 then 200 with the same message id.
- History page size, ordering and `before_seq` cursor.
- Non-member send and ticket issuance → 403.
- Two authenticated sockets received `ready`; WebSocket send produced an ack and
  a broadcast to the other member; the message was present in REST history.
- Reusing a consumed ticket was rejected (close code 4401).

Redis outage/recovery check: while the Redis container was stopped, a REST send
still returned **201** with sequence 2 and appeared in history; after Redis was
restarted, the next send returned 201 with sequence 3. Persistence and ordering
therefore do not depend on Redis availability.

A real defect was found and fixed: `before_seq` was bound as `beforeSeq`, so the
cursor was ignored and the newest page was returned. The parameter is now
`@RequestParam(name = "before_seq")` and the cursor test passes.

## 4. Automated test evidence

| Test | Result |
|---|---|
| `MessagingIT` | **4 run, 0 failures** |
| Concurrent sends / unique sequence | PASS (20 sends, sorted 1..20) |
| Duplicate client id | PASS — one row, second response 200 |
| Non-member read/send | PASS — 403 |
| Ticket single use / expiry key removal | PASS |
| Publish only after commit | PASS — rollback produced 0 publishes and 0 rows; commit produced 1 |
| `RedisGroupPublisherFailureTest` | PASS — Redis failure is swallowed, no loss of persisted message path |
| P1–P3 regression ITs | PASS |
| Full `mvn -q verify` | **PASS** — 21 unit tests, 17 integration tests, 0 skipped |
| Frontend lint / typecheck / build | PASS — 6 routes, 109 kB first load |

## 5. Decisions

1. Message sends lock the group row, then check `client_msg_id`, allocate the next
   sequence, insert and register an after-commit publish. This serializes sends
   per group and makes duplicates harmless.
2. Redis fan-out is best-effort: persistence succeeds even if publish fails; a
   failed publish is logged without message content.
3. Tickets are 32-byte random values; only their SHA-256 is stored, with a
   60-second TTL and `GETDEL` consumption. The access token never appears in a
   WebSocket URL.
4. One group per socket, matching the documented protocol. TCP/WebSocket sessions
   are wrapped with per-session synchronization because `WebSocketSession` is not
   safe for concurrent sends.
5. Received message ids are de-duplicated per group before local broadcast.

## 6. Deferred / risks

- Cross-pod fan-out is implemented through Redis pub/sub but only a single pod
  was exercised live; multi-pod delivery remains a P6 load item.
- A single-pod Redis outage/recovery was validated live; multi-pod chaos,
  reconnect under load and p95 delivery measurements remain P6.
- Heartbeat is a 30-second server ping; pong-timeout enforcement is not automated
  in P4.
- No chat UI was added; the P1 frontend remains, with P5 owning chat screens.
- Message attachments, read receipts and push notifications are out of scope.

P4 acceptance passed. P5 has not been started.
