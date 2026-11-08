package com.example.data.network

import com.example.data.model.ChatMessageDto
import com.example.data.model.TicketRequestDto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import kotlin.math.min
import kotlin.random.Random

sealed class WsChatEvent {
    data class Ready(val groupId: String, val lastSequenceId: Long) : WsChatEvent()
    data class IncomingMessage(val message: ChatMessageDto) : WsChatEvent()
    data class Ack(
        val clientMsgId: String,
        val messageId: String,
        val sequenceId: Long,
        val createdAt: String
    ) : WsChatEvent()
    data class FrameError(val code: String, val clientMsgId: String?) : WsChatEvent()
}

/**
 * Manages the per-group WebSocket connection (`WS /v1/ws?ticket=<opaque>`) according
 * to `docs/lld.md` §7 and `web/app/groups/[id]/chat/page.tsx`:
 *
 * 1. Single-use tickets: fetches a fresh ticket via `POST /v1/realtime/tickets` on EVERY
 *    initial connection and EVERY reconnect attempt. Never reuses a ticket.
 * 2. Handshake & `ready` frame: once `{"type":"ready","group_id":...,"last_sequence_id":...}`
 *    arrives, emits [WsChatEvent.Ready] so the repository can backfill missed messages via REST.
 * 3. Reconnect with exponential backoff (1s, 2s, 4s, 8s, 16s, 30s) + ±20% jitter.
 */
class GroupChatWebSocketClient(
    private val groupId: String,
    private val baseUrlProvider: () -> String,
    private val okHttpClient: OkHttpClient,
    private val apiService: KinshipApiService,
    private val externalScope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<WsChatEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsChatEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var connectJob: Job? = null
    private var closedByClient = false
    private var attempt = 0

    fun start() {
        closedByClient = false
        attempt = 0
        scheduleConnect(0L)
    }

    fun stop() {
        closedByClient = true
        connectJob?.cancel()
        webSocket?.close(1000, "Client closed chat")
        webSocket = null
        _connected.value = false
    }

    fun sendFrame(body: String, clientMsgId: String): Boolean {
        val ws = webSocket ?: return false
        val payload = buildJsonObject {
            put("type", "msg")
            put("body", body)
            put("client_msg_id", clientMsgId)
        }.toString()
        return ws.send(payload)
    }

    private fun scheduleConnect(delayMs: Long) {
        connectJob?.cancel()
        connectJob = externalScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (closedByClient) return@launch
            try {
                // Single-use ticket: ALWAYS fetch a fresh ticket via REST before opening WS
                val ticketResponse = apiService.issueWsTicket(TicketRequestDto(groupId = groupId))
                if (closedByClient) return@launch
                val wsUrl = buildWsUrl(baseUrlProvider(), ticketResponse.ticket)
                val request = Request.Builder().url(wsUrl).build()
                webSocket = okHttpClient.newWebSocket(request, Listener())
            } catch (_: Exception) {
                _connected.value = false
                if (!closedByClient) {
                    scheduleReconnectWithBackoff()
                }
            }
        }
    }

    private fun scheduleReconnectWithBackoff() {
        if (closedByClient) return
        val baseDelaySec = min(30, 1 shl min(attempt, 5))
        attempt++
        val jitterFactor = 0.8 + Random.nextDouble(0.0, 0.4) // ±20% jitter
        val delayMs = (baseDelaySec * 1000.0 * jitterFactor).toLong()
        scheduleConnect(delayMs)
    }

    private fun buildWsUrl(httpBaseUrl: String, ticket: String): String {
        val wsBase = httpBaseUrl.trimEnd('/')
            .replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        return "$wsBase/v1/ws?ticket=${java.net.URLEncoder.encode(ticket, "UTF-8")}"
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "ready" -> {
                        _connected.value = true
                        val gId = obj["group_id"]?.jsonPrimitive?.contentOrNull ?: groupId
                        val lastSeq = obj["last_sequence_id"]?.jsonPrimitive?.longOrNull ?: 0L
                        _events.tryEmit(WsChatEvent.Ready(gId, lastSeq))
                    }
                    "msg" -> {
                        val msg = ChatMessageDto(
                            messageId = obj["message_id"]?.jsonPrimitive?.content ?: "",
                            sequenceId = obj["sequence_id"]?.jsonPrimitive?.long ?: 0L,
                            senderId = obj["sender_id"]?.jsonPrimitive?.content ?: "",
                            body = obj["body"]?.jsonPrimitive?.content ?: "",
                            createdAt = obj["created_at"]?.jsonPrimitive?.content ?: ""
                        )
                        _events.tryEmit(WsChatEvent.IncomingMessage(msg))
                    }
                    "ack" -> {
                        val clientId = obj["client_msg_id"]?.jsonPrimitive?.content ?: ""
                        val msgId = obj["message_id"]?.jsonPrimitive?.content ?: ""
                        val seq = obj["sequence_id"]?.jsonPrimitive?.long ?: 0L
                        val created = obj["created_at"]?.jsonPrimitive?.content ?: ""
                        _events.tryEmit(WsChatEvent.Ack(clientId, msgId, seq, created))
                    }
                    "error" -> {
                        val code = obj["code"]?.jsonPrimitive?.contentOrNull ?: "ERROR"
                        val clientId = obj["client_msg_id"]?.jsonPrimitive?.contentOrNull
                        _events.tryEmit(WsChatEvent.FrameError(code, clientId))
                    }
                }
            } catch (_: Exception) {
                // Ignore malformed frames
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
            if (!closedByClient && code != 4403) {
                scheduleReconnectWithBackoff()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connected.value = false
            if (!closedByClient) {
                scheduleReconnectWithBackoff()
            }
        }
    }
}
