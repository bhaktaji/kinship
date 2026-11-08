# Backend Considerations for Mobile (`BACKEND_CHANGES.md`)

Per project rules, **no files in `api/` were modified**. The Android client works out-of-the-box against the existing Spring Boot backend (`api/`). Below are observations and optional backend enhancements recommended for future mobile iterations.

---

## 1. Refresh Token Cookie Path (`Path=/v1/auth`)
- **Current Backend Behaviour (`AuthController.java`):**
  - `POST /v1/auth/login`, `/v1/auth/register`, and `/v1/auth/refresh` emit the `kinship_rt` refresh token inside an `HttpOnly` `Set-Cookie` header scoped to `Path=/v1/auth; SameSite=Lax`.
- **How Android Handles It Today:**
  - `TokenStore` implements `okhttp3.CookieJar`, captures `Set-Cookie: kinship_rt=...` from `/v1/auth/*` responses, encrypts it inside `EncryptedSharedPreferences`, and attaches `Cookie: kinship_rt=<token>` only when calling `/v1/auth/*` endpoints.
- **Optional Future Backend Enhancement:**
  - Allowing an optional header or request-body fallback for native mobile clients (or returning a device-bound token) would simplify non-browser clients, though the current encrypted `CookieJar` + single-flight `Mutex` `Authenticator` works seamlessly with the existing cookie flow.

---

## 2. Offline RSVP Outbox Ordering & Client Timestamps (`POST /v1/events/{id}/rsvp`)
- **Current Backend Behaviour (`EventController.java`):**
  - `POST /v1/events/{id}/rsvp` atomically updates the caller's RSVP status and returns `409 EVENT_FULL` if `confirmed_count >= capacity`.
- **How Android Handles It Today:**
  - `RsvpOutboxManager` deduplicates queued offline RSVPs per `eventId` in Room so only the user's latest intent (`GOING`, `MAYBE`, or `NOT_GOING`) is sent when `RsvpOutboxSyncWorker` runs.
- **Optional Future Backend Enhancement:**
  - Accepting an optional `client_updated_at` timestamp or `Idempotency-Key` header on `POST /v1/events/{id}/rsvp` would prevent an older background WorkManager sync on Device A from overwriting a newer RSVP made on Device B (or the web app) while Device A was offline.

---

## 3. Push Notifications vs. 30-Second Polling (`NotificationController.java`)
- **Current Backend Behaviour:**
  - Notifications are stored in PostgreSQL and polled via `GET /v1/notifications` or `GET /v1/notifications/unread-count`.
- **Optional Future Backend Enhancement:**
  - When moving beyond polling to Firebase Cloud Messaging (FCM), add `POST /v1/users/me/push-tokens` (`{ "token": "...", "platform": "ANDROID" }`) and `DELETE /v1/users/me/push-tokens/{token}` so `NotificationService` can dispatch high-priority data messages on `NEW_MESSAGE`, `MATCH_INVITE`, and `EVENT_REMINDER`.
