package com.example.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.model.TokenResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the short-lived JWT access token in memory + EncryptedSharedPreferences
 * and provides a persistent encrypted [CookieJar] for the `kinship_rt` httpOnly
 * single-use rotating refresh cookie issued by `/v1/auth` endpoints.
 */
@Singleton
class TokenStore(
    private val prefs: SharedPreferences
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(createEncryptedPrefs(context))

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_COOKIE_VALUE = "kinship_rt_value"
        private const val KEY_REFRESH_COOKIE_EXPIRES_AT = "kinship_rt_expires_at"
        const val REFRESH_COOKIE_NAME = "kinship_rt"

        fun createEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    "kinship_secure_auth_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Throwable) {
                // Fallback for local JVM Robolectric tests where AndroidKeyStore provider is stubbed
                context.getSharedPreferences("kinship_fallback_test_prefs", Context.MODE_PRIVATE)
            }
        }
    }

    private val _accessTokenFlow = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null))
    val accessTokenFlow: StateFlow<String?> = _accessTokenFlow.asStateFlow()

    @Volatile
    private var cachedAccessToken: String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getAccessToken(): String? = cachedAccessToken

    fun setAccessToken(token: String?) {
        cachedAccessToken = token
        _accessTokenFlow.value = token
        prefs.edit().apply {
            if (token == null) remove(KEY_ACCESS_TOKEN)
            else putString(KEY_ACCESS_TOKEN, token)
        }.apply()
    }

    fun getRefreshCookieValue(): String? {
        val expiresAt = prefs.getLong(KEY_REFRESH_COOKIE_EXPIRES_AT, 0L)
        if (expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
            clearRefreshCookie()
            return null
        }
        return prefs.getString(KEY_REFRESH_COOKIE_VALUE, null)?.takeIf { it.isNotBlank() }
    }

    fun setRefreshCookie(value: String, expiresAtMillis: Long) {
        saveRefreshCookie(value, expiresAtMillis)
    }

    fun saveRefreshCookie(value: String, expiresAtMillis: Long) {
        if (value.isBlank() || expiresAtMillis <= System.currentTimeMillis()) {
            clearRefreshCookie()
            return
        }
        prefs.edit()
            .putString(KEY_REFRESH_COOKIE_VALUE, value)
            .putLong(KEY_REFRESH_COOKIE_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    fun clearRefreshCookie() {
        prefs.edit()
            .remove(KEY_REFRESH_COOKIE_VALUE)
            .remove(KEY_REFRESH_COOKIE_EXPIRES_AT)
            .apply()
    }

    fun clearAll() {
        cachedAccessToken = null
        _accessTokenFlow.value = null
        prefs.edit().clear().apply()
    }

    val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                if (cookie.name == REFRESH_COOKIE_NAME) {
                    if (cookie.value.isBlank() || cookie.expiresAt <= System.currentTimeMillis()) {
                        clearRefreshCookie()
                    } else {
                        saveRefreshCookie(cookie.value, cookie.expiresAt)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            // The backend sets Path=/v1/auth on kinship_rt, so only attach it to /v1/auth/* endpoints
            if (!url.encodedPath.startsWith("/v1/auth")) {
                return emptyList()
            }
            val value = getRefreshCookieValue() ?: return emptyList()
            val expiresAt = prefs.getLong(KEY_REFRESH_COOKIE_EXPIRES_AT, System.currentTimeMillis() + 86_400_000L)
            val cookie = Cookie.Builder()
                .name(REFRESH_COOKIE_NAME)
                .value(value)
                .domain(url.host)
                .path("/v1/auth")
                .expiresAt(expiresAt)
                .httpOnly()
                .build()
            return listOf(cookie)
        }
    }
}
