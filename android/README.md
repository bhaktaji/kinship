# Kinship for Android

Native Android client (`minSdk 26`, `targetSdk 36`) for **Kinship** — the small-group recurring activities platform. Built with Kotlin, Jetpack Compose (Material 3), Hilt, Retrofit + OkHttp + `kotlinx.serialization`, Navigation Compose, Room, WorkManager, DataStore, `EncryptedSharedPreferences` (Android Keystore), and Google Play Services `FusedLocationProviderClient`.

---

## 1. Running the Android Emulator Against the Docker Compose Stack

### Step 1: Start the Kinship Backend Stack
From the root of the repository (`kinship/`), start PostgreSQL/PostGIS, Redis, and the Spring Boot API:

```bash
docker compose up --build
```

Verify that the API is healthy on your host machine:
```bash
curl http://localhost:8080/v1/health
# -> {"status":"UP"}
```

### Step 2: Open & Run the Android Project
1. Open the `android/` directory in **Android Studio Ladybug (or newer)**.
2. Select the **`debug`** build variant.
   - In `debug` builds, `BuildConfig.API_BASE_URL` is preconfigured to **`http://10.0.2.2:8080`** (the Android Emulator's special alias for your host machine's `127.0.0.1:8080`).
   - Cleartext HTTP traffic to `10.0.2.2`, `localhost`, and `127.0.0.1` is permitted **only** in `app/src/debug/res/xml/network_security_config.xml`. Release builds enforce HTTPS (`cleartextTrafficPermitted="false"`).
3. Run the app on an Android Emulator (API 26+).

### Step 3: Sign In with the Seeded Demo Account
- On the Login screen, tap **"Fill Demo"** (or sign in manually):
  - **Email:** `demo@kinship.local`
  - **Password:** `Demo12345!`
- The backend seeds 25 Berlin users, 12 activities, 20 groups, and 8 weeks of recurring occurrences centered around **Central Berlin (`52.5200, 13.4050`)**.
- **Location tip:** By default, a fresh Android Emulator sets its simulated GPS coordinates to Mountain View, CA (`37.4220, -122.0840`). You can either:
  1. Use the in-app **Neighbourhood Picker** on the Discover screen (`Central Berlin`, `Kreuzberg`, `Neukölln`, `Prenzlauer Berg`, `Friedrichshain`, `Charlottenburg`), **or**
  2. Set the emulator's GPS coordinates to `52.5200, 13.4050` in Extended Controls (`...` -> **Location**) and tap the **GPS** button.

---

## 2. Testing on a Physical Android Phone

### Option A: USB Debugging with `adb reverse` (Recommended)
Connect your phone via USB with USB Debugging enabled and forward device port `8080` to your computer's `localhost:8080`:

```bash
adb reverse tcp:8080 tcp:8080
```

Then build with `-PAPI_BASE_URL=http://127.0.0.1:8080` (already whitelisted in the debug network security config), or set `buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8080\"")` in `app/build.gradle.kts`.

### Option B: Same Wi-Fi / LAN IP
1. Find your computer's LAN IP (e.g., `192.168.1.42`).
2. Add `<domain includeSubdomains="false">192.168.1.42</domain>` to `app/src/debug/res/xml/network_security_config.xml`.
3. Point the debug `API_BASE_URL` in `app/build.gradle.kts` to `"http://192.168.1.42:8080"`.

---

## 3. Architecture Overview

| Layer | Package / Key Classes | Responsibility |
|---|---|---|
| **UI & Navigation** | `com.example.ui.screens.*`, `com.example.ui.components.*`, `MainActivity.kt` | Light-only Material 3 Compose UI (`#F7F4EE` paper, `#FFFDF9` surface, `#1C1B18` ink, `#2D4839` forest, `#B64C2B` terracotta, `#687E60` sage, `#BC8B2C` gold), bundled `Fraunces` + `Inter` fonts, local `VectorDrawable` category artwork (no remote images). |
| **State Management** | `com.example.ui.KinshipViewModel` | `@HiltViewModel` exposing reactive `StateFlow`s for Auth, 3-step Onboarding, Discovery, Groups, Events, WebSocket Chat, Matches, Notifications, and Profile. |
| **Repository** | `com.example.data.repository.KinshipRepository` | Coordinates Retrofit API calls, `ETag` / `If-None-Match` conditional GETs, Room caching, and offline RSVP queueing. |
| **Auth & Single-Flight Refresh** | `TokenStore.kt`, `TokenRefreshAuthenticator.kt` | Stores `access_token` and the `HttpOnly` `kinship_rt` refresh cookie (`Path=/v1/auth`) inside `EncryptedSharedPreferences` (`MasterKey.KeyScheme.AES256_GCM`). Serializes all `POST /v1/auth/refresh` calls with a Kotlin Coroutines `Mutex` so concurrent `401` responses never replay a single-use refresh token. Handles `429` with `Retry-After` backoff. |
| **Realtime WebSocket Chat** | `GroupChatWebSocketClient.kt` | Fetches a fresh single-use ticket (`POST /v1/realtime/tickets`) on **every** connect and reconnect, connects to `WS /v1/ws?ticket=...`, backfills history via REST upon receiving `ready`, deduplicates via `client_msg_id`, and reconnects with exponential backoff (1s -> 30s) + ±20% jitter. |
| **Offline Persistence** | `KinshipDatabase.kt`, `RsvpOutboxManager.kt`, `RsvpOutboxSyncWorker.kt` | Room database storing `cached_discovery_pages` (with `ETag`) and `rsvp_outbox` (ported from `web/lib/outbox.ts`: 20-item cap, 24h expiry, 4xx drop, 5xx/network retention) synced by a `@HiltWorker` `CoroutineWorker` when `NetworkType.CONNECTED` is available. |

---

## 4. Running Unit Tests

Run the local JVM + Robolectric test suite (`TokenRefreshAuthenticatorTest` and `RsvpOutboxManagerTest`):

```bash
gradle :app:testDebugUnitTest
```
