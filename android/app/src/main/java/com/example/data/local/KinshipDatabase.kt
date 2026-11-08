package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Stores cached discovery pages (`GET /v1/activities`) along with their HTTP strong `ETag`
 * so `If-None-Match` conditional GETs return `304 Not Modified` and reuse the Room payload,
 * and offline users can browse cached activities (`docs/mobile_optimization.md` §4.2–4.3).
 */
@Entity(tableName = "cached_discovery_pages")
data class CachedDiscoveryPageEntity(
    @PrimaryKey val queryKey: String,
    val etag: String?,
    val payloadJson: String,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Port of `web/lib/outbox.ts` (`IndexedDB` store `outbox`) to Room.
 *
 * Rules from `web/lib/outbox.ts` & `docs/mobile_optimization.md` §4.3:
 * - Natural deduplication key `id = "$method $url $body"` so repeated offline toggles
 *   overwrite the queued RSVP for that event rather than stacking duplicates.
 * - Capped at 20 entries (`MAX_ACTIONS = 20`); oldest entries beyond 19 are trimmed on insert.
 * - Entries older than 24 hours (`MAX_AGE_MS = 24 * 60 * 60 * 1000`) are expired and dropped on flush.
 * - 4xx API responses are dropped immediately because they can never succeed;
 *   transport/5xx failures halt the flush and retain remaining entries for WorkManager retry.
 */
@Entity(tableName = "rsvp_outbox")
data class RsvpOutboxEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val url: String,
    val method: String = "POST",
    val status: String,
    val bodyJson: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface DiscoveryCacheDao {
    @Query("SELECT * FROM cached_discovery_pages WHERE queryKey = :queryKey LIMIT 1")
    suspend fun getByQueryKey(queryKey: String): CachedDiscoveryPageEntity?

    @Query("SELECT * FROM cached_discovery_pages ORDER BY updatedAtMillis DESC LIMIT 1")
    suspend fun getLatestPage(): CachedDiscoveryPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(entity: CachedDiscoveryPageEntity)

    @Query("DELETE FROM cached_discovery_pages")
    suspend fun clearAll()
}

@Dao
interface RsvpOutboxDao {
    @Query("SELECT * FROM rsvp_outbox ORDER BY createdAtMillis ASC")
    suspend fun listAllOrdered(): List<RsvpOutboxEntity>

    @Query("SELECT * FROM rsvp_outbox ORDER BY createdAtMillis ASC")
    fun observeAllOrdered(): Flow<List<RsvpOutboxEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<RsvpOutboxEntity>)

    @Query("DELETE FROM rsvp_outbox WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM rsvp_outbox")
    suspend fun clearAll()
}

@Database(
    entities = [CachedDiscoveryPageEntity::class, RsvpOutboxEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KinshipDatabase : RoomDatabase() {
    abstract fun discoveryCacheDao(): DiscoveryCacheDao
    abstract fun rsvpOutboxDao(): RsvpOutboxDao
}
