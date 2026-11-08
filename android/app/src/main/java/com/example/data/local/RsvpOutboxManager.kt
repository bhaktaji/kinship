package com.example.data.local

import android.content.Context
import androidx.work.*
import com.example.data.model.KinshipApiException
import com.example.data.model.RsvpRequestDto
import com.example.data.network.KinshipApiService
import com.example.data.network.toKinshipApiException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class OutboxFlushResult(
    val sent: Int,
    val dropped: Int,
    val remaining: Int
)

/**
 * Port of `web/lib/outbox.ts` backed by Room (`RsvpOutboxDao`) and scheduled via WorkManager.
 *
 * Key invariants preserved from `web/lib/outbox.ts`:
 * - `MAX_ACTIONS = 20`
 * - `MAX_AGE_MS = 24 * 60 * 60 * 1000` (24 hours)
 * - Deduplicates by natural key (`POST /v1/events/{id}/rsvp` per event)
 * - Drops >24h expired entries and 4xx permanent API rejections
 * - Stops flush on network/5xx error so WorkManager can retry when connectivity returns
 */
@Singleton
class RsvpOutboxManager @Inject constructor(
    private val outboxDao: RsvpOutboxDao,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        const val MAX_ACTIONS = 20
        const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val WORK_NAME = "kinship_rsvp_outbox_sync"
    }

    private val flushMutex = Mutex()

    fun observePendingActions(nowProvider: () -> Long = { System.currentTimeMillis() }): Flow<List<RsvpOutboxEntity>> {
        return outboxDao.observeAllOrdered().map { list ->
            val now = nowProvider()
            list.filter { now - it.createdAtMillis < MAX_AGE_MS }
        }
    }

    suspend fun pendingCount(nowMs: Long = System.currentTimeMillis()): Int {
        return outboxDao.listAllOrdered().count { nowMs - it.createdAtMillis < MAX_AGE_MS }
    }

    /**
     * Queues an RSVP action while offline and trims expired or excess (>20) actions.
     */
    suspend fun queueRsvp(
        eventId: String,
        status: String,
        nowMs: Long = System.currentTimeMillis()
    ): RsvpOutboxEntity {
        val url = "/v1/events/$eventId/rsvp"
        val bodyJson = json.encodeToString(RsvpRequestDto(status = status))
        // Deduplicate per event endpoint so changing GOING -> NOT_GOING offline replaces the prior queued RSVP
        val id = "POST $url"
        val current = outboxDao.listAllOrdered()
        val fresh = current.filter { nowMs - it.createdAtMillis < MAX_AGE_MS }
        val withoutDuplicate = fresh.filter { it.id != id && it.eventId != eventId }
        val trimmed = withoutDuplicate.takeLast(MAX_ACTIONS - 1)
        val newEntry = RsvpOutboxEntity(
            id = id,
            eventId = eventId,
            url = url,
            method = "POST",
            status = status,
            bodyJson = bodyJson,
            createdAtMillis = nowMs
        )
        outboxDao.clearAll()
        outboxDao.insertAll(trimmed + newEntry)
        return newEntry
    }

    suspend fun clearOutbox() {
        outboxDao.clearAll()
    }

    /**
     * Flushes queued RSVP actions in chronological order.
     *
     * @param executor suspending callback that performs the API call for a single [RsvpOutboxEntity].
     */
    suspend fun flushOutbox(
        nowMs: Long = System.currentTimeMillis(),
        executor: suspend (RsvpOutboxEntity) -> Unit
    ): OutboxFlushResult {
        if (!flushMutex.tryLock()) {
            return OutboxFlushResult(sent = 0, dropped = 0, remaining = pendingCount(nowMs))
        }
        var sent = 0
        var dropped = 0
        try {
            val actions = outboxDao.listAllOrdered()
            for (action in actions) {
                if (nowMs - action.createdAtMillis >= MAX_AGE_MS) {
                    outboxDao.deleteById(action.id)
                    dropped++
                    continue
                }
                try {
                    executor(action)
                    outboxDao.deleteById(action.id)
                    sent++
                } catch (t: Throwable) {
                    val apiEx = t.toKinshipApiException()
                    if (apiEx.status in 400..499) {
                        // 4xx error (e.g. 409 EVENT_FULL / EVENT_STARTED) can never succeed on replay -> drop
                        outboxDao.deleteById(action.id)
                        dropped++
                        continue
                    }
                    // Network or 5xx failure -> stop flushing and retain remaining items for next connectivity sync
                    break
                }
            }
            val remaining = pendingCount(nowMs)
            return OutboxFlushResult(sent = sent, dropped = dropped, remaining = remaining)
        } finally {
            flushMutex.unlock()
        }
    }

    fun scheduleSyncWork(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<RsvpOutboxSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        } catch (_: Exception) {
            // Ignored in unit tests if WorkManager is not initialized
        }
    }
}
