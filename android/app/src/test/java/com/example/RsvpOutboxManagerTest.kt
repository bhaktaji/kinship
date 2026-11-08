package com.example

import com.example.data.local.RsvpOutboxDao
import com.example.data.local.RsvpOutboxEntity
import com.example.data.local.RsvpOutboxManager
import com.example.data.model.KinshipApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests verifying the Room RSVP Outbox (`RsvpOutboxManager` + `RsvpOutboxDao`),
 * ported from `web/lib/outbox.ts`:
 * 1. Deduplication by event ID (updating `MAYBE` -> `GOING` offline replaces the existing row).
 * 2. Enforcing the 20-action cap (`MAX_ACTIONS = 20`) by evicting the oldest entries.
 * 3. Dropping actions older than 24 hours (`MAX_AGE_MS`).
 * 4. Dropping permanent 4xx errors (e.g. `409 EVENT_FULL`) during flush so they don't block the queue.
 * 5. Retaining actions when a network/5xx error occurs so WorkManager can retry on reconnect.
 */
class RsvpOutboxManagerTest {

    private lateinit var outboxDao: InMemoryRsvpOutboxDao
    private lateinit var outboxManager: RsvpOutboxManager

    @Before
    fun setUp() {
        outboxDao = InMemoryRsvpOutboxDao()
        outboxManager = RsvpOutboxManager(outboxDao = outboxDao)
    }

    @Test
    fun `queueRsvp deduplicates same eventId and updates status`() = runBlocking {
        val now = 1_700_000_000_000L
        outboxManager.queueRsvp(eventId = "event-101", status = "MAYBE", nowMs = now)
        outboxManager.queueRsvp(eventId = "event-101", status = "GOING", nowMs = now + 5_000L)

        val queued = outboxDao.listAllOrdered()
        assertEquals(1, queued.size)
        assertEquals("event-101", queued.first().eventId)
        assertEquals("GOING", queued.first().status)
    }

    @Test
    fun `queueRsvp enforces 20-action maximum cap`() = runBlocking {
        val baseTime = 1_700_000_000_000L
        for (i in 1..25) {
            outboxManager.queueRsvp(
                eventId = "event-$i",
                status = "GOING",
                nowMs = baseTime + i * 1_000L
            )
        }

        val queued = outboxDao.listAllOrdered()
        assertEquals(RsvpOutboxManager.MAX_ACTIONS, queued.size)
        // Oldest 5 (event-1..event-5) should be evicted; newest 20 (event-6..event-25) kept
        assertEquals("event-6", queued.first().eventId)
        assertEquals("event-25", queued.last().eventId)
    }

    @Test
    fun `flushOutbox drops expired 24h entries and 409 EVENT_FULL conflicts`() = runBlocking {
        val now = 1_700_100_000_000L
        val expiredTime = now - RsvpOutboxManager.MAX_AGE_MS - 10_000L

        outboxManager.queueRsvp(eventId = "conflict-409-ev", status = "GOING", nowMs = now - 5_000L)
        outboxManager.queueRsvp(eventId = "valid-ev", status = "MAYBE", nowMs = now - 1_000L)
        // Insert an expired entry alongside the fresh items so flushOutbox evaluates all three
        outboxDao.insertAll(
            listOf(
                RsvpOutboxEntity(
                    id = "POST /v1/events/expired-ev/rsvp",
                    eventId = "expired-ev",
                    url = "/v1/events/expired-ev/rsvp",
                    method = "POST",
                    status = "GOING",
                    bodyJson = """{"status":"GOING"}""",
                    createdAtMillis = expiredTime
                )
            )
        )

        val result = outboxManager.flushOutbox(nowMs = now) { entity ->
            when (entity.eventId) {
                "conflict-409-ev" -> throw KinshipApiException(
                    status = 409,
                    code = "EVENT_FULL",
                    message = "Event is at full capacity"
                )
                "valid-ev" -> {
                    // Succeeds
                }
            }
        }

        assertEquals(1, result.sent)
        assertEquals(2, result.dropped) // 1 expired + 1 409 conflict dropped
        assertEquals(0, result.remaining)
        assertTrue(outboxDao.listAllOrdered().isEmpty())
    }

    @Test
    fun `flushOutbox stops and retains remaining actions on network failure for WorkManager retry`() = runBlocking {
        val now = 1_700_100_000_000L
        outboxManager.queueRsvp(eventId = "ev-1", status = "GOING", nowMs = now - 3_000L)
        outboxManager.queueRsvp(eventId = "ev-2", status = "MAYBE", nowMs = now - 2_000L)
        outboxManager.queueRsvp(eventId = "ev-3", status = "NOT_GOING", nowMs = now - 1_000L)

        val result = outboxManager.flushOutbox(nowMs = now) { entity ->
            if (entity.eventId == "ev-2") {
                throw IOException("Network unreachable")
            }
        }

        // ev-1 succeeded; ev-2 threw IOException so ev-2 and ev-3 remain queued
        assertEquals(1, result.sent)
        assertEquals(0, result.dropped)
        assertEquals(2, result.remaining)

        val remainingIds = outboxDao.listAllOrdered().map { it.eventId }
        assertEquals(listOf("ev-2", "ev-3"), remainingIds)
    }
}

private class InMemoryRsvpOutboxDao : RsvpOutboxDao {
    private val itemsFlow = MutableStateFlow<List<RsvpOutboxEntity>>(emptyList())

    override fun observeAllOrdered(): Flow<List<RsvpOutboxEntity>> =
        itemsFlow.map { list -> list.sortedBy { it.createdAtMillis } }

    override suspend fun listAllOrdered(): List<RsvpOutboxEntity> =
        itemsFlow.value.sortedBy { it.createdAtMillis }

    override suspend fun insertAll(entities: List<RsvpOutboxEntity>) {
        val ids = entities.map { it.id }.toSet()
        itemsFlow.value = itemsFlow.value.filterNot { it.id in ids } + entities
    }

    override suspend fun deleteById(id: String) {
        itemsFlow.value = itemsFlow.value.filterNot { it.id == id }
    }

    override suspend fun clearAll() {
        itemsFlow.value = emptyList()
    }
}
