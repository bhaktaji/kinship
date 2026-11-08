# Kinship — Recurring-Activity Friendship Platform
## Documentation Index

A mobile-first PWA that helps adults build local friendships through small,
recurring activity groups. Core loop:
**discover activity → join group → attend recurring events → message the group**.

**Status:** P0–P7 implemented and verified on a local Docker stack — 2026-11-08.
Authentication, discovery, groups/events/RSVP, matching/invitations, WebSocket messaging,
engagement features, PWA, and the redesigned UI pass their live and automated checks.
See `../README.md` for the current run guide and `../PHASE_1.md` … `../PHASE_7.md` for
phase evidence. The project is not production-certified; see `../PROJECT_CLOSURE.md`.

---

## Document Map

| File | Purpose | Primary reader |
|---|---|---|
| `hld.md` | Architecture, MVP scope, NFRs, security, decisions | Architect, Tech Lead |
| `lld.md` | API contracts, auth/authz, algorithms, concurrency, errors | Backend Engineers |
| `erd.md` | Schema, enums, relationships, invariants | Backend, DBA |
| `implementation_plan.md` | Phase sequence, dependencies, DoD, prerequisites | Tech Lead, PM |
| `testing_plan.md` | Test levels, critical scenarios, phase gates, load/chaos | QA, Backend |
| `mobile_optimization.md` | Frontend performance and PWA strategy | Frontend Engineers |
| `ci_integration.txt` | CI pipeline, quality gates, local parity | DevOps, Engineers |
| `delivery_phase_prompt.md` | Executable per-phase instructions for a coding agent | Implementing agent |
| `implementation_prompt.txt` | Agent bootstrap: mission, stack, rules | Implementing agent |
| `indexes.sql` | Index DDL applied by `V2` (plus `V4` job uniqueness) | Backend, DBA |
| `rsvp_race_script.js` | k6 capacity-race test | QA |
| `k6_discovery_smoke.js` | P1 discovery smoke (5 VUs, 2 min) | QA |
| `../README.md` | Run instructions, configuration | Engineers |
| `../PHASE_1.md` | P1 foundation report | Tech Lead |
| `../PHASE_2.md` | P2 groups, events and RSVP report | Tech Lead |
| `../PHASE_3.md` | P3 matching and invitations report | Tech Lead |
| `../PHASE_4.md` | P4 messaging and WebSocket report | Tech Lead |
| `../PHASE_5.md` | P5 web app, PWA and E2E report | Tech Lead |
| `../PHASE_6.md` | P6 load, soak and failure-injection report | Tech Lead |
| `../PHASE_7.md` | P7 engagement, onboarding and community report | Tech Lead |
| `../SECURITY_AUDIT.md` | First-party security assessment | Security reviewer |
| `../PROJECT_CLOSURE.md` | Handover record and outstanding items | Tech Lead |

---

## Stack (decided)

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3.x, Maven 3.9.9, Spring MVC, Spring Security, Spring JDBC, Flyway |
| Database | PostgreSQL 16 + PostGIS 3.4 |
| Cache / sessions / pub-sub | Redis 7 (Lettuce) |
| Frontend | Next.js 15 App Router, React 19, TypeScript, Tailwind CSS, TanStack Query, service worker |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers, Playwright, k6 |
| Local runtime | Docker Compose (preferred) or native PostgreSQL/Redis, documented in Phase 1 |

The earlier "Node.js or Python" placeholder is superseded — see `hld.md` §12 for
the decision and its rationale.

---

## Reading Order for an Implementation Agent

1. `hld.md` §1–§4 and §12 — scope, modules, confirmed decisions.
2. `erd.md` → `indexes.sql` — schema first.
3. `lld.md` — implement exactly these contracts.
4. `implementation_plan.md` — phase order, exit criteria, prerequisites.
5. `delivery_phase_prompt.md` — the phase you are executing.
6. `testing_plan.md` — the gate you must pass before exiting the phase.

---

## Conventions

- **Activity** = discoverable offering at a location; **group** = persistent small
  circle; **event series** = recurrence definition; **occurrence** = one dated event;
  **match** = async proposed group.
- Times are stored in UTC; local rendering uses the user's IANA zone.
- All list endpoints use keyset pagination.
- `hld.md` §12 separates **confirmed decisions**, **assumptions to validate**, and
  **open items requiring owner input**; keep that separation when editing docs.
- Documents are the source of truth; code changes that alter a contract update the
  matching document in the same change.

---

## Environment Snapshot

The verified local environment used Java 21 (Temurin), Maven 3.9.9, Node 22, Docker
Compose with PostgreSQL 16 + PostGIS 3.4, and Redis 7. See `../README.md` §Prerequisites
for the current requirements and setup commands.

---

## Guiding Principle

> Optimize for repeated **in-person interaction**, not online engagement.
> Cache reads aggressively, keep writes idempotent, and never block the
> discovery path on async work.
