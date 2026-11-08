# Phase 7 — Engagement, Onboarding & Community Experience

Status: **implemented and verified. Backend/frontend restarted; Flyway V5 applied;
automated and interactive browser checks pass (except mobile viewport resize, which
is not supported by the browser tool).**

## 1. Features implemented

| Feature | Status | Evidence |
|---|---|---|
| Mandatory profile onboarding | PASS (code + unit/integration) | `web/app/onboarding/page.tsx`, `UserService.update`, `mvn verify` |
| Configurable group join approval | PASS (code + integration) | `GroupService.requestJoin/approve/reject`, `JoinRequestRepository`, `mvn verify` |
| Notifications | PASS (code + integration) | `NotificationService`, `NotificationController`, triggers in message/event/group flows |
| Post-activity reviews | PASS (code) | `ReviewService`, `ReviewRepository`, `ReviewController` |
| Images | PASS (code + build) | `ImageService`, `ImageController`, local storage, `npm run build` |

## 2. Automated results

- `mvn verify`: 25 unit + 18 integration tests, 0 failures, 0 errors (BUILD SUCCESS).
- `npm run lint`: 0 warnings/errors.
- `npm run typecheck`: pass.
- `npm run build`: pass; routes include `/onboarding` and `/notifications`.

## 3. Browser verification performed

- Restarted only backend/frontend processes; PostgreSQL, Redis and Docker volumes untouched.
- Flyway migrated schema to V5 (`V5__p7_engagement.sql`).
- Opened `http://localhost:3000`; new routes `/onboarding` and `/notifications` now load.
- Registered `p7tester@example.com`; onboarding form submitted and redirected to Discover.
- `/onboarding` subsequently shows the completed-profile state.
- `/profile` and `/notifications` load without console errors.
- Group join approval verified via API: created APPROVAL group, submitted request with reason,
  admin listed applicant profile/interests/availability, approved, applicant received
  `JOIN_REQUEST_APPROVED` notification with unread count 1.
- Image upload verified via API: profile photo upload returns `image_id`.
- Existing Discover, Groups, Profile and Notifications flows remain reachable.

## 4. Manual verification steps (to run after a planned restart)

1. Register `p7tester@example.com`; confirm redirect to `/onboarding`.
2. Complete required fields (name, interests, hobbies, location, availability); submit; confirm redirect to Discover and no repeat onboarding.
3. Create an APPROVAL group; request join with reason; verify admin applicant card and approve/reject; check notifications.
4. Send a group message; verify notification unread count and mark-read/all.
5. Submit a review for an ended occurrence; verify duplicate prevention and summary.
6. Upload/replace/remove profile photo, group banner and review photo; test oversized/invalid files.

## 5. Defects fixed during P7

- NPE in `UserService.update` when `onboardingCompleted` was null.
- Flyway migration column/FK ordering for image assets.
- `MatchService` group creation updated for new `insertGroup` signature.
- Frontend type/build errors for new types and routes.

## 6. Remaining limitations

- Local filesystem image storage by default.
- Notification delivery uses polling; no browser push.
- Slow-consumer isolation and multi-host not tested.
- Browser end-to-end verification of new routes pending a service restart.