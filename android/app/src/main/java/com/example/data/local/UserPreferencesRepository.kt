package com.example.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private val Context.kinshipDataStore: DataStore<Preferences> by preferencesDataStore(name = "kinship_preferences")

data class UserPrefsState(
    val cachedUserJson: String? = null,
    val manualLat: Double = 52.5200,
    val manualLng: Double = 13.4050,
    val manualAreaLabel: String = "Central Berlin",
    val radiusKm: Int = 10,
    val categoryFilter: String = "",
    val groupSizeMaxFilter: Int? = null,
    val apiBaseUrlOverride: String? = null
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val CACHED_USER_JSON = stringPreferencesKey("cached_user_json")
        val MANUAL_LAT = doublePreferencesKey("manual_lat")
        val MANUAL_LNG = doublePreferencesKey("manual_lng")
        val MANUAL_AREA_LABEL = stringPreferencesKey("manual_area_label")
        val RADIUS_KM = intPreferencesKey("radius_km")
        val CATEGORY_FILTER = stringPreferencesKey("category_filter")
        val GROUP_SIZE_MAX = intPreferencesKey("group_size_max")
        val API_BASE_URL_OVERRIDE = stringPreferencesKey("api_base_url_override")
    }

    val prefsFlow: Flow<UserPrefsState> = context.kinshipDataStore.data.map { prefs ->
        UserPrefsState(
            cachedUserJson = prefs[Keys.CACHED_USER_JSON],
            manualLat = prefs[Keys.MANUAL_LAT] ?: 52.5200,
            manualLng = prefs[Keys.MANUAL_LNG] ?: 13.4050,
            manualAreaLabel = prefs[Keys.MANUAL_AREA_LABEL] ?: "Central Berlin",
            radiusKm = prefs[Keys.RADIUS_KM] ?: 10,
            categoryFilter = prefs[Keys.CATEGORY_FILTER] ?: "",
            groupSizeMaxFilter = prefs[Keys.GROUP_SIZE_MAX],
            apiBaseUrlOverride = prefs[Keys.API_BASE_URL_OVERRIDE]
        )
    }

    suspend fun saveCachedUserJson(json: String?) {
        context.kinshipDataStore.edit { prefs ->
            if (json == null) prefs.remove(Keys.CACHED_USER_JSON)
            else prefs[Keys.CACHED_USER_JSON] = json
        }
    }

    suspend fun saveDiscoveryLocation(lat: Double, lng: Double, areaLabel: String) {
        context.kinshipDataStore.edit { prefs ->
            prefs[Keys.MANUAL_LAT] = lat
            prefs[Keys.MANUAL_LNG] = lng
            prefs[Keys.MANUAL_AREA_LABEL] = areaLabel
        }
    }

    suspend fun saveDiscoveryFilters(radiusKm: Int, category: String, groupSizeMax: Int?) {
        context.kinshipDataStore.edit { prefs ->
            prefs[Keys.RADIUS_KM] = radiusKm
            prefs[Keys.CATEGORY_FILTER] = category
            if (groupSizeMax == null) prefs.remove(Keys.GROUP_SIZE_MAX)
            else prefs[Keys.GROUP_SIZE_MAX] = groupSizeMax
        }
    }

    suspend fun clearSessionCache() {
        context.kinshipDataStore.edit { prefs ->
            prefs.remove(Keys.CACHED_USER_JSON)
        }
    }
}

/**
 * Provides device GPS coordinates via Google Play Services `FusedLocationProviderClient`
 * when runtime location permission (`ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`) is granted,
 * with graceful fallback to manual city/neighbourhood entry (e.g., Central Berlin `52.5200, 13.4050`).
 */
@Singleton
class FusedLocationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocationOrNull(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            suspendCancellableCoroutine { cont ->
                val tokenSource = CancellationTokenSource()
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location ->
                        if (cont.isActive) {
                            if (location != null) {
                                cont.resume(location.latitude to location.longitude)
                            } else {
                                cont.resume(null)
                            }
                        }
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
                cont.invokeOnCancellation { tokenSource.cancel() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
