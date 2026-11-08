# Feature Parity Matrix (Web → Native Android)

This document maps every route, library module, and user flow in the Next.js web application (`web/app/**` and `web/lib/**`) to its native Android implementation (`app/src/main/java/com/example/**`).

## 1. Route & Screen Mapping

| Web Route / Component | Web Source | Android Screen / Component | Status | Notes |
|---|---|---|---|---|
| **Login** (`/login`) | `web/app/login/page.tsx` | `LoginScreen` (`AuthAndOnboardingScreens.kt`) | **Full Parity** | Includes one-tap fill for `demo@kinship.local` / `Demo12345!`, error banner, and post-login redirect to Onboarding if `onboarding_completed == false`. |
| **Register** (`/register`) | `web/app/register/page.tsx` | `RegisterScreen` (`AuthAndOnboardingScreens.kt`) | **Full Parity** | Validates display name (2–80 chars), email, and password (≥ 8 chars), then launches 3-step onboarding. |
| **3-Step Guided Onboarding** (`/onboarding`) | `web/app/onboarding/page.tsx` | `OnboardingScreen` (`AuthAndOnboardingScreens.kt`) | **Full Parity** | Step 1: Display name, bio, profession, workplace + share toggle, location. Step 2: Interests taxonomy (≥ 2) + hobbies. Step 3: Weekly availability slots. |
| **Discovery** (`/`) | `web/app/page.tsx` | `DiscoverScreen` (`DiscoverAndActivityScreens.kt`) | **Full Parity + Native GPS** | PostGIS radius search (`3/5/10/25 km`), category chips, max group size filter, keyword search, cursor pagination (`next_page_token`), `ETag`/`If-None-Match` conditional caching in Room, plus runtime `FusedLocationProviderClient` GPS permission with manual Berlin neighbourhood fallback. |
| **Activity Detail** (`/activities/[id]`) | `web/app/activities/[id]/page.tsx` | `ActivityDetailScreen` (`DiscoverAndActivityScreens.kt`) | **Full Parity** | Displays activity description, organization summary, location, and all active circles with their next scheduled occurrence and capacity meter. |
| **My Groups & Create Group** (`/groups`) | `web/app/groups/page.tsx` | `GroupsListScreen` + `CreateGroupDialog` (`GroupAndEventScreens.kt`) | **Full Parity** | Lists user's active/pending circles (`GET /v1/groups?mine=true`) and provides a modal to create a new public/private or instant/approval group (`POST /v1/groups`). |
| **Group Detail & Owner Controls** (`/groups/[id]`) | `web/app/groups/[id]/page.tsx` | `GroupDetailScreen` + `CreateRecurringSeriesDialog` (`GroupAndEventScreens.kt`) | **Full Parity** | Instant join (`POST /v1/groups/{id}/join`), approval join request (`POST /v1/groups/{id}/join-requests`), leave group, owner approval/removal of members (`PATCH /v1/groups/{id}/members/{userId}`), owner creation of recurring weekly/biweekly/monthly event series (`POST /v1/groups/{id}/event-series`), and group review summary + reporting. |
| **Event Detail & RSVP** (`/events/[id]`) | `web/app/events/[id]/page.tsx` | `EventDetailScreen` (`GroupAndEventScreens.kt`) | **Full Parity** | RSVP `GOING` / `MAYBE` / `NOT_GOING` (`POST /v1/events/{id}/rsvp`), explicit `409 EVENT_FULL` capacity conflict banner, offline Room outbox queueing, and post-occurrence review submission (`POST /v1/occurrences/{id}/reviews`). |
| **Group Chat (WebSocket)** (`/groups/[id]/chat`) | `web/app/groups/[id]/chat/page.tsx` | `GroupChatScreen` (`ChatMatchesNotificationsProfileScreens.kt`) + `GroupChatWebSocketClient.kt` | **Full Parity** | Fetches a fresh single-use ticket (`POST /v1/realtime/tickets`) on every connect/reconnect, connects to `WS /v1/ws?ticket=...`, backfills history via `GET /v1/groups/{id}/messages` on `ready`, deduplicates via `client_msg_id`, and supports loading older messages via `before_seq`. |
| **Algorithmic Matches & Invitations** (`/invitations`) | `web/app/invitations/page.tsx` | `MatchesScreen` (`ChatMatchesNotificationsProfileScreens.kt`) | **Full Parity** | Triggers async matching (`POST /v1/match-requests`), polls `GET /v1/match-requests/{jobId}`, and displays 48-hour invitations (`GET /v1/match-invitations`) with `Accept` / `Decline` actions. |
| **Notifications** (`/notifications`) | `web/app/notifications/page.tsx`, `web/components/AppHeader.tsx` | `NotificationsScreen` + `AppTopHeader` (`CommonComponents.kt`) | **Full Parity** | 30-second background polling while authenticated, unread badge count in top bar, mark individual notification read, mark all read, and deep-link navigation to target group/event/match. |
| **Profile & Image Upload** (`/profile`) | `web/app/profile/page.tsx` | `ProfileScreen` (`ChatMatchesNotificationsProfileScreens.kt`) | **Full Parity** | Edit profile fields, interests, hobbies, workplace sharing, and upload profile photos using the Android Photo Picker (`PickVisualMedia`) validated for ≤ 5 MB and `jpg/png/webp/gif` (`POST /v1/images?type=PROFILE`). |
| **Category Artwork** | `web/components/CategoryArt.tsx` | `CategoryArtworkBanner` + `res/drawable/ic_category_*.xml` | **Full Parity** | All 7 category illustrations (`music`, `sport`, `outdoors`, `food`, `books`, `art`, `default`) ported to native Android `VectorDrawable` XML assets with zero remote images. |

---

## 2. Infrastructure & Client Library Mapping

| Web Module | Android Equivalent | Details |
|---|---|---|
| `web/lib/api.ts` (Single-flight refresh) | `TokenRefreshAuthenticator.kt` + `TokenStore.kt` | Persists `access_token` and `kinship_rt` cookie in `EncryptedSharedPreferences`. Serializes `POST /v1/auth/refresh` inside a Kotlin `Mutex` so concurrent 401s never replay a single-use refresh token. |
| `web/lib/outbox.ts` (IndexedDB RSVP outbox) | `RsvpOutboxManager.kt` + `RsvpOutboxSyncWorker.kt` (`Room` + `WorkManager`) | Preserves the 20-action cap, 24-hour expiry, per-event deduplication, 4xx drop, and network/5xx retry via `WorkManager` (`NetworkType.CONNECTED`). |
| `web/lib/auth-context.tsx` (Session & logout purge) | `KinshipRepository.logout()` / `purgeSessionData()` | Clears `EncryptedSharedPreferences` tokens/cookies, Room discovery cache, Room RSVP outbox, and DataStore user cache on logout or account switch. |

---

## 3. What Is Not Implemented (By Design)

1. **Firebase Cloud Messaging (FCM) Push Notifications:** Per project specification, notifications use 30-second REST polling (`GET /v1/notifications`) matching the web app; no FCM integration is included yet.
2. **Production Release HTTPS Domain:** `release` builds in `app/build.gradle.kts` use a placeholder HTTPS URL (`https://api.kinship.example.com`) with cleartext traffic disabled (`res/xml/network_security_config.xml`). Replace this placeholder with your production domain before publishing.
3. **Group Banner / Review Photo Gallery Viewer:** While the image upload endpoint (`POST /v1/images`) and profile photo upload flow (`PickVisualMedia` with 5 MB + MIME validation) are fully implemented, group cards and activity cards exclusively render the deterministic local `VectorDrawable` category artwork as required by the design brief (no remote images).
