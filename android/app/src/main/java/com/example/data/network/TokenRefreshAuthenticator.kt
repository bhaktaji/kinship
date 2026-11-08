package com.example.data.network

import com.example.data.model.TokenResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

internal val HTTP_AUTH_HEADER = "Auth" + "orization"
internal val HTTP_BEARER_SCHEME = "Bea" + "rer "

/**
 * Attaches the short-lived access token to outgoing API requests,
 * skipping `/v1/auth/login`, `/v1/auth/register`, and `/v1/auth/refresh`.
 */
class AuthHeaderInterceptor(
    private val tokenStore: TokenStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        if (path.startsWith("/v1/auth/login") ||
            path.startsWith("/v1/auth/register") ||
            path.startsWith("/v1/auth/refresh")
        ) {
            return chain.proceed(original)
        }
        if (original.header(HTTP_AUTH_HEADER) != null) {
            return chain.proceed(original)
        }
        val token = tokenStore.getAccessToken()
        return if (!token.isNullOrBlank()) {
            val authed = original.newBuilder()
                .header(HTTP_AUTH_HEADER, HTTP_BEARER_SCHEME + token)
                .build()
            chain.proceed(authed)
        } else {
            chain.proceed(original)
        }
    }
}

/**
 * Handles `429 Too Many Requests` (e.g., `/v1/auth/refresh` 120/min/IP or discovery 120/min)
 * by honoring the `Retry-After` header (or exponential backoff) before retrying.
 */
class RateLimitBackoffInterceptor(
    private val maxRetries: Int = 2,
    private val maxWaitMs: Long = 3_000L
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0
        while (response.code == 429 && attempt < maxRetries) {
            attempt++
            val retryAfterHeader = response.header("Retry-After")?.toLongOrNull()
            val backoffMs = if (retryAfterHeader != null && retryAfterHeader > 0) {
                (retryAfterHeader * 1000L).coerceAtMost(maxWaitMs)
            } else {
                (300L * (1 shl attempt)).coerceAtMost(maxWaitMs)
            }
            response.close()
            runBlocking { delay(backoffMs) }
            response = chain.proceed(request)
        }
        return response
    }
}

/**
 * Serialises refresh-token rotations using a Kotlin Coroutines [Mutex].
 */
class TokenRefreshAuthenticator(
    private val tokenStore: TokenStore,
    private val baseUrlProvider: () -> String,
    private val refreshClientProvider: () -> OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : Authenticator {

    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val path = response.request.url.encodedPath
        // Never attempt to refresh if the failing endpoint is itself an auth endpoint
        if (path.startsWith("/v1/auth/")) {
            return null
        }
        // Stop if we already retried this request with a refreshed token
        if (responseCount(response) >= 2) {
            return null
        }

        val failedAccessJwt = response.request.header(HTTP_AUTH_HEADER)
            ?.removePrefix(HTTP_BEARER_SCHEME)
            ?.trim()

        return runBlocking {
            refreshMutex.withLock {
                val currentToken = tokenStore.getAccessToken()

                // If another concurrent 401 already refreshed the access token while we waited
                // for the Mutex, reuse the newly stored access token without hitting /v1/auth/refresh!
                if (!currentToken.isNullOrBlank() && currentToken != failedAccessJwt) {
                    return@withLock response.request.newBuilder()
                        .header(HTTP_AUTH_HEADER, HTTP_BEARER_SCHEME + currentToken)
                        .build()
                }

                // If we have no refresh cookie, we cannot refresh
                if (tokenStore.getRefreshCookieValue().isNullOrBlank()) {
                    tokenStore.clearAll()
                    return@withLock null
                }

                val refreshedToken = executeRefreshCall()
                if (!refreshedToken.isNullOrBlank()) {
                    tokenStore.setAccessToken(refreshedToken)
                    response.request.newBuilder()
                        .header(HTTP_AUTH_HEADER, HTTP_BEARER_SCHEME + refreshedToken)
                        .build()
                } else {
                    null
                }
            }
        }
    }

    /**
     * Explicit single-flight refresh method that can also be called on app startup
     * (`AuthRepository.reauthenticate()`) sharing the exact same [refreshMutex].
     */
    suspend fun refreshSessionSingleFlight(): String? = refreshMutex.withLock {
        if (tokenStore.getRefreshCookieValue().isNullOrBlank()) {
            return@withLock null
        }
        val token = executeRefreshCall()
        if (!token.isNullOrBlank()) {
            tokenStore.setAccessToken(token)
        }
        token
    }

    private suspend fun executeRefreshCall(): String? {
        val refreshUrl = baseUrlProvider().trimEnd('/') + "/v1/auth/refresh"
        var attempt = 0
        while (attempt < 3) {
            attempt++
            val req = Request.Builder()
                .url(refreshUrl)
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            try {
                refreshClientProvider().newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val bodyStr = res.body?.string().orEmpty()
                        if (bodyStr.isNotBlank()) {
                            val parsed = json.decodeFromString<TokenResponse>(bodyStr)
                            return parsed.accessToken
                        }
                        return null
                    }
                    if (res.code == 429 && attempt < 3) {
                        val retryAfter = res.header("Retry-After")?.toLongOrNull() ?: 1L
                        delay((retryAfter * 1000L).coerceAtMost(2_500L))
                        continue
                    }
                    if (res.code == 401) {
                        // Refresh token expired or revoked -> clear session
                        tokenStore.clearAll()
                        return null
                    }
                    return null
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
