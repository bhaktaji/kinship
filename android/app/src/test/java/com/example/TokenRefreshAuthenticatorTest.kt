package com.example

import android.content.SharedPreferences
import com.example.data.network.AuthHeaderInterceptor
import com.example.data.network.RateLimitBackoffInterceptor
import com.example.data.network.TokenRefreshAuthenticator
import com.example.data.network.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests verifying:
 * 1. Single-flight `Mutex` serialization inside [TokenRefreshAuthenticator] when multiple
 *    requests receive `401 Unauthorized` concurrently — ensuring `POST /v1/auth/refresh`
 *    is called EXACTLY ONCE so single-use `kinship_rt` cookies are never replayed.
 * 2. Session cleanup when `/v1/auth/refresh` rejects an expired/replayed refresh token with `401`.
 * 3. `429 Too Many Requests` backoff via [RateLimitBackoffInterceptor].
 */
class TokenRefreshAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = TokenStore(InMemorySharedPreferences())
    }

    @After
    fun tearDown() {
        server.shutdown()
        tokenStore.clearAll()
    }

    @Test
    fun `concurrent 401 responses trigger exactly one refresh call via Mutex`() = runBlocking {
        val refreshCallCount = AtomicInteger(0)
        val initialAccessToken = "expired_access_jwt_v1"
        val rotatedAccessToken = "fresh_access_jwt_v2"

        tokenStore.setAccessToken(initialAccessToken)
        tokenStore.setRefreshCookie(
            value = "single_use_rt_v1",
            expiresAtMillis = System.currentTimeMillis() + 600_000L
        )

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/v1/auth/refresh" -> {
                        val callNum = refreshCallCount.incrementAndGet()
                        val cookieHeader = request.getHeader("Cookie").orEmpty()
                        // Simulate single-use refresh token rotation: only the first call with rt_v1 succeeds!
                        if (callNum == 1 && cookieHeader.contains("kinship_rt=single_use_rt_v1")) {
                            Thread.sleep(60) // Hold refresh in flight so concurrent 401s queue on Mutex
                            MockResponse()
                                .setResponseCode(200)
                                .addHeader("Set-Cookie", "kinship_rt=single_use_rt_v2; Path=/v1/auth; HttpOnly; Max-Age=2592000")
                                .setBody("""{"access_token":"$rotatedAccessToken","token_type":"Bearer","expires_in":900}""")
                        } else {
                            // If a second refresh were ever attempted with the old token, backend revokes family (401)
                            MockResponse()
                                .setResponseCode(401)
                                .setBody("""{"error":{"code":"REFRESH_REUSED","message":"Refresh token reuse detected"}}""")
                        }
                    }
                    "/v1/users/me" -> {
                        val auth = request.getHeader("Auth" + "orization")
                        if (auth == ("Bea" + "rer ") + rotatedAccessToken) {
                            MockResponse()
                                .setResponseCode(200)
                                .setBody("""{"id":"u1","email":"demo@kinship.local","display_name":"Mara"}""")
                        } else {
                            MockResponse()
                                .setResponseCode(401)
                                .setBody("""{"error":{"code":"UNAUTHENTICATED","message":"Expired JWT"}}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val refreshClient = OkHttpClient.Builder()
            .cookieJar(tokenStore.cookieJar)
            .build()

        val authenticator = TokenRefreshAuthenticator(
            tokenStore = tokenStore,
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            refreshClientProvider = { refreshClient }
        )

        val apiClient = OkHttpClient.Builder()
            .cookieJar(tokenStore.cookieJar)
            .addInterceptor(AuthHeaderInterceptor(tokenStore))
            .authenticator(authenticator)
            .build()

        // Launch 5 concurrent requests that all start with the expired access token
        val deferredResponses = (1..5).map {
            async(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(server.url("/v1/users/me"))
                    .get()
                    .build()
                apiClient.newCall(req).execute().use { res ->
                    res.code
                }
            }
        }

        val statusCodes = deferredResponses.awaitAll()

        // Every concurrent request must succeed with 200 after the single-flight refresh
        assertEquals(listOf(200, 200, 200, 200, 200), statusCodes)
        // CRITICAL: /v1/auth/refresh must have been called EXACTLY ONCE
        assertEquals(1, refreshCallCount.get())
        // TokenStore must hold the rotated access token and rotated cookie
        assertEquals(rotatedAccessToken, tokenStore.getAccessToken())
        assertEquals("single_use_rt_v2", tokenStore.getRefreshCookieValue())
    }

    @Test
    fun `401 on refresh endpoint clears session tokens and does not loop`() {
        tokenStore.setAccessToken("expired_jwt")
        tokenStore.setRefreshCookie(
            value = "revoked_rt",
            expiresAtMillis = System.currentTimeMillis() + 600_000L
        )

        server.enqueue(MockResponse().setResponseCode(401)) // Initial /v1/users/me 401
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"REFRESH_REUSED","message":"Revoked"}}""")
        )

        val refreshClient = OkHttpClient.Builder().cookieJar(tokenStore.cookieJar).build()
        val authenticator = TokenRefreshAuthenticator(
            tokenStore = tokenStore,
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            refreshClientProvider = { refreshClient }
        )
        val apiClient = OkHttpClient.Builder()
            .cookieJar(tokenStore.cookieJar)
            .addInterceptor(AuthHeaderInterceptor(tokenStore))
            .authenticator(authenticator)
            .build()

        val req = Request.Builder().url(server.url("/v1/users/me")).get().build()
        apiClient.newCall(req).execute().use { res ->
            assertEquals(401, res.code)
        }

        assertNull(tokenStore.getAccessToken())
        assertNull(tokenStore.getRefreshCookieValue())
    }

    @Test
    fun `RateLimitBackoffInterceptor retries on 429 Too Many Requests`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "1")
                .setBody("""{"error":{"code":"RATE_LIMITED","message":"Too many requests"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok"}""")
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(RateLimitBackoffInterceptor(maxRetries = 2, maxWaitMs = 200L))
            .build()

        val req = Request.Builder().url(server.url("/v1/activities")).get().build()
        client.newCall(req).execute().use { res ->
            assertEquals(200, res.code)
        }
        assertEquals(2, server.requestCount)
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = HashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { staged[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { staged[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearRequested) map.clear()
            for ((k, v) in staged) {
                if (v == null) map.remove(k) else map[k] = v
            }
        }
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
