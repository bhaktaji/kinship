package com.example.data.local

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.model.RsvpRequestDto
import com.example.data.network.KinshipApiService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background WorkManager worker that flushes queued offline RSVPs as soon as
 * network connectivity (`NetworkType.CONNECTED`) is available.
 */
@HiltWorker
class RsvpOutboxSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxManager: RsvpOutboxManager,
    private val apiService: KinshipApiService
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val flushResult = outboxManager.flushOutbox { entity ->
            apiService.submitRsvp(
                eventId = entity.eventId,
                request = RsvpRequestDto(status = entity.status)
            )
        }
        return if (flushResult.remaining > 0) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
