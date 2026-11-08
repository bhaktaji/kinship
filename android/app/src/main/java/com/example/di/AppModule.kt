package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.BuildConfig
import com.example.data.local.DiscoveryCacheDao
import com.example.data.local.KinshipDatabase
import com.example.data.local.RsvpOutboxDao
import com.example.data.network.AuthHeaderInterceptor
import com.example.data.network.KinshipApiService
import com.example.data.network.RateLimitBackoffInterceptor
import com.example.data.network.TokenRefreshAuthenticator
import com.example.data.network.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Provides
    @Singleton
    @Named("ApiBaseUrl")
    fun provideApiBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    @Singleton
    @Named("RefreshOkHttp")
    fun provideRefreshOkHttpClient(
        tokenStore: TokenStore
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(tokenStore.cookieJar)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideTokenRefreshAuthenticator(
        tokenStore: TokenStore,
        @Named("ApiBaseUrl") apiBaseUrl: String,
        @Named("RefreshOkHttp") refreshClient: OkHttpClient,
        json: Json
    ): TokenRefreshAuthenticator {
        return TokenRefreshAuthenticator(
            tokenStore = tokenStore,
            baseUrlProvider = { apiBaseUrl },
            refreshClientProvider = { refreshClient },
            json = json
        )
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenStore: TokenStore,
        authenticator: TokenRefreshAuthenticator
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // Never log sensitive auth or cookie headers
            redactHeader("Auth" + "orization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .cookieJar(tokenStore.cookieJar)
            .addInterceptor(AuthHeaderInterceptor(tokenStore))
            .addInterceptor(RateLimitBackoffInterceptor())
            .addInterceptor(logging)
            .authenticator(authenticator)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideKinshipApiService(
        okHttpClient: OkHttpClient,
        @Named("ApiBaseUrl") apiBaseUrl: String,
        json: Json
    ): KinshipApiService {
        val contentType = "application/json".toMediaType()
        val baseUrl = apiBaseUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(KinshipApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideKinshipDatabase(
        @ApplicationContext context: Context
    ): KinshipDatabase {
        return Room.databaseBuilder(
            context,
            KinshipDatabase::class.java,
            "kinship_offline.db"
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    @Provides
    fun provideDiscoveryCacheDao(db: KinshipDatabase): DiscoveryCacheDao = db.discoveryCacheDao()

    @Provides
    fun provideRsvpOutboxDao(db: KinshipDatabase): RsvpOutboxDao = db.rsvpOutboxDao()
}
