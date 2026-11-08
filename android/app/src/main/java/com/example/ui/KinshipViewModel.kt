package com.example.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.FusedLocationHelper
import com.example.data.local.RsvpOutboxEntity
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.*
import com.example.data.network.GroupChatWebSocketClient
import com.example.data.network.WsChatEvent
import com.example.data.repository.DemoSeedCatalog
import com.example.data.repository.KinshipRepository
import com.example.data.repository.RsvpSubmissionOutcome
import com.example.ui.components.BannerTone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class UiBannerMessage(
    val text: String,
    val tone: BannerTone = BannerTone.INFO
)

data class DiscoveryUiState(
    val items: List<ActivityCardDto> = emptyList(),
    val nextPageToken: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val lat: Double = 52.5200,
    val lng: Double = 13.4050,
    val areaLabel: String = "Central Berlin",
    val isGpsActive: Boolean = false,
    val radiusKm: Int = 10,
    val category: String = "",
    val groupSizeMax: Int? = null,
    val searchQuery: String = "",
    val error: String? = null
)

data class ChatUiMessage(
    val messageId: String,
    val clientMsgId: String? = null,
    val sequenceId: Long,
    val senderId: String,
    val body: String,
    val createdAt: String,
    val isPendingAck: Boolean = false
)

@HiltViewModel
class KinshipViewModel @Inject constructor(
    private val repository: KinshipRepository,
    private val userPrefsRepo: UserPreferencesRepository,
    private val locationHelper: FusedLocationHelper
) : ViewModel() {

    val currentUser: StateFlow<UserDto?> = repository.currentUser
    val authLoading: StateFlow<Boolean> = repository.authLoading
    val isOfflineOrPreview: StateFlow<Boolean> = repository.isOfflineOrPreview
    val unreadNotificationCount: StateFlow<Int> = repository.unreadNotificationCount
    val pendingOutboxItems: StateFlow<List<RsvpOutboxEntity>> = repository.pendingOutboxFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _bannerMessage = MutableStateFlow<UiBannerMessage?>(null)
    val bannerMessage: StateFlow<UiBannerMessage?> = _bannerMessage.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _authSubmitting = MutableStateFlow(false)
    val authSubmitting: StateFlow<Boolean> = _authSubmitting.asStateFlow()

    private val _taxonomyInterests = MutableStateFlow<List<InterestResponseDto>>(DemoSeedCatalog.taxonomyInterests)
    val taxonomyInterests: StateFlow<List<InterestResponseDto>> = _taxonomyInterests.asStateFlow()

    private val _discoveryState = MutableStateFlow(DiscoveryUiState())
    val discoveryState: StateFlow<DiscoveryUiState> = _discoveryState.asStateFlow()

    private val _selectedActivity = MutableStateFlow<ActivityDetailDto?>(null)
    val selectedActivity: StateFlow<ActivityDetailDto?> = _selectedActivity.asStateFlow()

    private val _myGroups = MutableStateFlow<List<GroupCardDto>>(emptyList())
    val myGroups: StateFlow<List<GroupCardDto>> = _myGroups.asStateFlow()

    private val _selectedGroup = MutableStateFlow<GroupDetailDto?>(null)
    val selectedGroup: StateFlow<GroupDetailDto?> = _selectedGroup.asStateFlow()

    private val _selectedGroupReviews = MutableStateFlow(ReviewSummaryDto())
    val selectedGroupReviews: StateFlow<ReviewSummaryDto> = _selectedGroupReviews.asStateFlow()

    private val _selectedEvent = MutableStateFlow<EventDetailDto?>(null)
    val selectedEvent: StateFlow<EventDetailDto?> = _selectedEvent.asStateFlow()

    private val _eventConflictError = MutableStateFlow<String?>(null)
    val eventConflictError: StateFlow<String?> = _eventConflictError.asStateFlow()

    private val _matchInvitations = MutableStateFlow<List<MatchInvitationDto>>(emptyList())
    val matchInvitations: StateFlow<List<MatchInvitationDto>> = _matchInvitations.asStateFlow()

    private val _latestMatchJob = MutableStateFlow<MatchJobResponseDto?>(null)
    val latestMatchJob: StateFlow<MatchJobResponseDto?> = _latestMatchJob.asStateFlow()

    private val _matchesLoading = MutableStateFlow(false)
    val matchesLoading: StateFlow<Boolean> = _matchesLoading.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationDto>>(emptyList())
    val notifications: StateFlow<List<NotificationDto>> = _notifications.asStateFlow()

    // Chat state
    private var activeChatClient: GroupChatWebSocketClient? = null
    private var chatCollectJob: Job? = null
    private var activeChatGroupId: String? = null

    private val _chatMessages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatUiMessage>> = _chatMessages.asStateFlow()

    private val _chatWsConnected = MutableStateFlow(false)
    val chatWsConnected: StateFlow<Boolean> = _chatWsConnected.asStateFlow()

    private val _chatNextBeforeSeq = MutableStateFlow<Long?>(null)
    val chatNextBeforeSeq: StateFlow<Long?> = _chatNextBeforeSeq.asStateFlow()

    init {
        viewModelScope.launch {
            _taxonomyInterests.value = repository.getInterests()
        }
        viewModelScope.launch {
            val prefs = userPrefsRepo.prefsFlow.first()
            _discoveryState.update {
                it.copy(
                    lat = prefs.manualLat,
                    lng = prefs.manualLng,
                    areaLabel = prefs.manualAreaLabel,
                    radiusKm = prefs.radiusKm,
                    category = prefs.categoryFilter,
                    groupSizeMax = prefs.groupSizeMaxFilter
                )
            }
            refreshDiscovery()
            loadMyGroups()
            loadMatches()
            loadNotifications()
        }
        startNotificationPolling()
    }

    /**
     * Polls `/v1/notifications` every 30 seconds while authenticated, mirroring the web app
     * behaviour (`web/components/AppHeader.tsx`, no FCM required yet).
     */
    private fun startNotificationPolling() {
        viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                if (currentUser.value != null) {
                    repository.getNotifications().onSuccess { page ->
                        _notifications.value = page.items
                    }
                }
            }
        }
    }

    fun showBanner(text: String, tone: BannerTone = BannerTone.INFO) {
        _bannerMessage.value = UiBannerMessage(text, tone)
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun login(email: String, password: String, onSuccess: (UserDto) -> Unit) {
        viewModelScope.launch {
            _authSubmitting.value = true
            _authError.value = null
            val res = repository.login(email, password)
            _authSubmitting.value = false
            res.onSuccess { user ->
                refreshDiscovery()
                loadMyGroups()
                loadMatches()
                loadNotifications()
                onSuccess(user)
            }.onFailure { err ->
                _authError.value = err.message ?: "Invalid email or password"
            }
        }
    }

    fun register(displayName: String, email: String, password: String, onSuccess: (UserDto) -> Unit) {
        viewModelScope.launch {
            _authSubmitting.value = true
            _authError.value = null
            val res = repository.register(displayName, email, password)
            _authSubmitting.value = false
            res.onSuccess { user ->
                onSuccess(user)
            }.onFailure { err ->
                _authError.value = err.message ?: "Could not create account"
            }
        }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            closeGroupChat()
            repository.logout()
            onComplete()
        }
    }

    fun saveOnboardingOrProfile(
        request: UpdateProfileRequest,
        successMessage: String = "Profile saved",
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.updateProfile(request)
                .onSuccess {
                    showBanner(successMessage, BannerTone.SUCCESS)
                    onSuccess()
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not save profile", BannerTone.ERROR)
                }
        }
    }

    fun uploadProfilePhoto(uri: Uri) {
        viewModelScope.launch {
            repository.uploadImageUri(uri, type = "PROFILE")
                .onSuccess { assetId ->
                    repository.updateProfile(UpdateProfileRequest(profilePhotoAssetId = assetId))
                    showBanner("Profile photo uploaded (asset ${assetId.take(8)})", BannerTone.SUCCESS)
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Photo upload failed", BannerTone.ERROR)
                }
        }
    }

    // --- Discovery & FusedLocation ---

    fun refreshDiscovery() {
        viewModelScope.launch {
            val state = _discoveryState.value
            _discoveryState.update { it.copy(isLoading = true, error = null) }
            val result = repository.discoverActivities(
                lat = state.lat,
                lng = state.lng,
                radiusKm = state.radiusKm,
                category = state.category.ifBlank { null },
                groupSizeMax = state.groupSizeMax,
                pageToken = null
            )
            result.onSuccess { page ->
                _discoveryState.update {
                    it.copy(
                        items = page.items,
                        nextPageToken = page.nextPageToken,
                        isLoading = false,
                        error = null
                    )
                }
            }.onFailure { err ->
                _discoveryState.update {
                    it.copy(isLoading = false, error = err.message ?: "Could not load activities")
                }
            }
        }
    }

    fun loadMoreDiscovery() {
        val state = _discoveryState.value
        val nextToken = state.nextPageToken ?: return
        if (state.isLoadingMore) return

        viewModelScope.launch {
            _discoveryState.update { it.copy(isLoadingMore = true) }
            val result = repository.discoverActivities(
                lat = state.lat,
                lng = state.lng,
                radiusKm = state.radiusKm,
                category = state.category.ifBlank { null },
                groupSizeMax = state.groupSizeMax,
                pageToken = nextToken
            )
            result.onSuccess { page ->
                _discoveryState.update { current ->
                    val merged = (current.items + page.items).distinctBy { it.activityId + it.groupId }
                    current.copy(
                        items = merged,
                        nextPageToken = page.nextPageToken,
                        isLoadingMore = false
                    )
                }
            }.onFailure {
                _discoveryState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun updateDiscoveryFilters(
        category: String = _discoveryState.value.category,
        radiusKm: Int = _discoveryState.value.radiusKm,
        groupSizeMax: Int? = _discoveryState.value.groupSizeMax,
        searchQuery: String = _discoveryState.value.searchQuery
    ) {
        val filterChanged = category != _discoveryState.value.category ||
            radiusKm != _discoveryState.value.radiusKm ||
            groupSizeMax != _discoveryState.value.groupSizeMax

        _discoveryState.update {
            it.copy(
                category = category,
                radiusKm = radiusKm,
                groupSizeMax = groupSizeMax,
                searchQuery = searchQuery
            )
        }
        if (filterChanged) {
            viewModelScope.launch {
                userPrefsRepo.saveDiscoveryFilters(radiusKm, category, groupSizeMax)
            }
            refreshDiscovery()
        }
    }

    fun selectManualArea(label: String, lat: Double, lng: Double) {
        _discoveryState.update {
            it.copy(lat = lat, lng = lng, areaLabel = label, isGpsActive = false)
        }
        viewModelScope.launch {
            userPrefsRepo.saveDiscoveryLocation(lat, lng, label)
        }
        refreshDiscovery()
    }

    fun useDeviceGpsLocation() {
        viewModelScope.launch {
            val coords = locationHelper.fetchCurrentLocationOrNull()
            if (coords != null) {
                val (lat, lng) = coords
                // If emulator default GPS is in Mountain View (37.42, -122.08), inform user and allow easy switch back to Berlin
                val label = if (lat in 52.3..52.7 && lng in 13.1..13.7) {
                    "GPS · Berlin (${"%.3f".format(lat)}, ${"%.3f".format(lng)})"
                } else {
                    "GPS (${"%.3f".format(lat)}, ${"%.3f".format(lng)})"
                }
                _discoveryState.update {
                    it.copy(lat = lat, lng = lng, areaLabel = label, isGpsActive = true)
                }
                refreshDiscovery()
            } else {
                showBanner(
                    "GPS unavailable — using ${discoveryState.value.areaLabel} coordinates.",
                    BannerTone.WARNING
                )
            }
        }
    }

    // --- Activity, Group & Event details ---

    fun loadActivityDetail(activityId: String) {
        viewModelScope.launch {
            repository.getActivityDetail(activityId).onSuccess { detail ->
                _selectedActivity.value = detail
            }
        }
    }

    fun loadMyGroups() {
        viewModelScope.launch {
            repository.getMyGroups().onSuccess { page ->
                _myGroups.value = page.items
            }
        }
    }

    fun createGroup(request: CreateGroupRequest, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            repository.createGroup(request)
                .onSuccess { detail ->
                    _selectedGroup.value = detail
                    loadMyGroups()
                    showBanner("Created group \"${detail.group.name}\"", BannerTone.SUCCESS)
                    onCreated(detail.group.groupId)
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Failed to create group", BannerTone.ERROR)
                }
        }
    }

    fun loadGroupDetail(groupId: String) {
        viewModelScope.launch {
            repository.getGroupDetail(groupId).onSuccess { detail ->
                _selectedGroup.value = detail
            }
            _selectedGroupReviews.value = repository.getGroupReviews(groupId)
        }
    }

    fun joinGroup(groupId: String) {
        viewModelScope.launch {
            repository.joinGroup(groupId)
                .onSuccess { res ->
                    loadGroupDetail(groupId)
                    loadMyGroups()
                    val msg = if (res.status == "PENDING") {
                        "Join request submitted — awaiting owner approval."
                    } else {
                        "You joined the group!"
                    }
                    showBanner(msg, BannerTone.SUCCESS)
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not join group", BannerTone.ERROR)
                }
        }
    }

    fun requestJoinApproval(groupId: String, reason: String, shareWorkplace: Boolean) {
        viewModelScope.launch {
            repository.requestJoinGroup(groupId, reason, shareWorkplace)
                .onSuccess {
                    loadGroupDetail(groupId)
                    loadMyGroups()
                    showBanner("Approval request sent to the group owner.", BannerTone.SUCCESS)
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not submit join request", BannerTone.ERROR)
                }
        }
    }

    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            repository.leaveGroup(groupId)
                .onSuccess {
                    loadGroupDetail(groupId)
                    loadMyGroups()
                    showBanner("You left the group.", BannerTone.INFO)
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not leave group", BannerTone.ERROR)
                }
        }
    }

    fun updateMemberStatus(groupId: String, userId: String, status: String) {
        viewModelScope.launch {
            repository.updateMemberStatus(groupId, userId, status)
                .onSuccess {
                    loadGroupDetail(groupId)
                    val label = if (status == "ACTIVE") "Member approved" else "Member removed"
                    showBanner(label, BannerTone.SUCCESS)
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Member update failed", BannerTone.ERROR)
                }
        }
    }

    fun createRecurringSeries(groupId: String, request: CreateSeriesRequest, onSuccess: () -> Unit) {
        viewModelScope.launch {
            repository.createEventSeries(groupId, request)
                .onSuccess { res ->
                    loadGroupDetail(groupId)
                    showBanner("Created recurring series (${res.occurrences.size} upcoming dates generated)", BannerTone.SUCCESS)
                    onSuccess()
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not create event series", BannerTone.ERROR)
                }
        }
    }

    fun loadEventDetail(eventId: String) {
        _eventConflictError.value = null
        viewModelScope.launch {
            repository.getEventDetail(eventId).onSuccess { detail ->
                _selectedEvent.value = detail
            }
        }
    }

    fun submitRsvp(eventId: String, status: String, forceOfflineQueue: Boolean = false) {
        _eventConflictError.value = null
        viewModelScope.launch {
            repository.submitRsvp(eventId, status, forceOfflineQueue)
                .onSuccess { outcome ->
                    loadEventDetail(eventId)
                    when (outcome) {
                        is RsvpSubmissionOutcome.Synced -> {
                            showBanner("RSVP updated to $status", BannerTone.SUCCESS)
                        }
                        is RsvpSubmissionOutcome.QueuedOffline -> {
                            showBanner(
                                "Saved RSVP ($status) to offline Room outbox — WorkManager will sync when online.",
                                BannerTone.WARNING
                            )
                        }
                    }
                }
                .onFailure { err ->
                    val apiEx = err as? KinshipApiException
                    if (apiEx?.status == 409 || apiEx?.code == "EVENT_FULL") {
                        _eventConflictError.value = apiEx.message
                    } else {
                        showBanner(err.message ?: "Could not update RSVP", BannerTone.ERROR)
                    }
                }
        }
    }

    fun submitOccurrenceReview(
        occurrenceId: String,
        groupId: String,
        request: ReviewRequestDto,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            repository.submitReview(occurrenceId, groupId, request)
                .onSuccess {
                    _selectedGroupReviews.value = repository.getGroupReviews(groupId)
                    showBanner("Thank you! Your meetup review has been published.", BannerTone.SUCCESS)
                    onComplete()
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not submit review", BannerTone.ERROR)
                }
        }
    }

    fun reportReview(reviewId: String, reason: String) {
        viewModelScope.launch {
            repository.reportReview(reviewId, reason)
                .onSuccess {
                    showBanner("Review reported to moderators.", BannerTone.INFO)
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not report review", BannerTone.ERROR)
                }
        }
    }

    // --- Group Chat over WebSocket + REST History Backfill + Deduplication ---

    fun openGroupChat(groupId: String) {
        if (activeChatGroupId == groupId && activeChatClient != null) return
        closeGroupChat()
        activeChatGroupId = groupId
        loadGroupDetail(groupId)

        // 1. Load initial message history via REST
        viewModelScope.launch {
            repository.getMessageHistory(groupId, beforeSeq = null).onSuccess { page ->
                _chatNextBeforeSeq.value = page.nextBeforeSeq
                val sorted = page.items.sortedBy { it.sequenceId }.map { dto ->
                    ChatUiMessage(
                        messageId = dto.messageId,
                        sequenceId = dto.sequenceId,
                        senderId = dto.senderId,
                        body = dto.body,
                        createdAt = dto.createdAt
                    )
                }
                _chatMessages.value = sorted
            }
        }

        // 2. Open WebSocket with single-use ticket and listen for ready/msg/ack frames
        val client = repository.createWebSocketChatClient(groupId, viewModelScope)
        activeChatClient = client
        chatCollectJob = viewModelScope.launch {
            launch {
                client.connected.collect { isConnected ->
                    _chatWsConnected.value = isConnected
                }
            }
            launch {
                client.events.collect { event ->
                    when (event) {
                        is WsChatEvent.Ready -> {
                            // History backfill on connect/reconnect: fetch latest messages so no gap occurs
                            repository.getMessageHistory(groupId, beforeSeq = null).onSuccess { page ->
                                mergeChatMessages(page.items)
                            }
                        }
                        is WsChatEvent.IncomingMessage -> {
                            mergeChatMessages(listOf(event.message))
                        }
                        is WsChatEvent.Ack -> {
                            _chatMessages.update { list ->
                                list.map { item ->
                                    if (item.clientMsgId == event.clientMsgId || item.messageId == event.messageId) {
                                        item.copy(
                                            messageId = event.messageId,
                                            sequenceId = event.sequenceId,
                                            createdAt = event.createdAt,
                                            isPendingAck = false
                                        )
                                    } else {
                                        item
                                    }
                                }.distinctBy { it.messageId }.sortedBy { it.sequenceId }
                            }
                        }
                        is WsChatEvent.FrameError -> {
                            showBanner("Chat warning: ${event.code}", BannerTone.WARNING)
                        }
                    }
                }
            }
        }
        client.start()
    }

    private fun mergeChatMessages(incoming: List<ChatMessageDto>) {
        _chatMessages.update { existing ->
            val byId = existing.associateBy { it.messageId }.toMutableMap()
            incoming.forEach { dto ->
                // Also deduplicate optimistic message if same sender + body was awaiting ack
                val optimisticKey = byId.values.firstOrNull {
                    it.isPendingAck && it.senderId == dto.senderId && it.body == dto.body
                }?.messageId
                if (optimisticKey != null) {
                    byId.remove(optimisticKey)
                }
                byId[dto.messageId] = ChatUiMessage(
                    messageId = dto.messageId,
                    sequenceId = dto.sequenceId,
                    senderId = dto.senderId,
                    body = dto.body,
                    createdAt = dto.createdAt,
                    isPendingAck = false
                )
            }
            byId.values.sortedBy { it.sequenceId }
        }
    }

    fun loadOlderChatMessages() {
        val groupId = activeChatGroupId ?: return
        val beforeSeq = _chatNextBeforeSeq.value ?: return
        viewModelScope.launch {
            repository.getMessageHistory(groupId, beforeSeq = beforeSeq).onSuccess { page ->
                _chatNextBeforeSeq.value = page.nextBeforeSeq
                mergeChatMessages(page.items)
            }
        }
    }

    fun sendChatMessage(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        val groupId = activeChatGroupId ?: return
        val senderId = currentUser.value?.id ?: DemoSeedCatalog.demoUser.id
        val clientMsgId = UUID.randomUUID().toString()
        val tempSeq = (_chatMessages.value.maxOfOrNull { it.sequenceId } ?: 0L) + 1L

        // Optimistic insertion with clientMsgId for duplicate-message protection
        val optimistic = ChatUiMessage(
            messageId = "pending-$clientMsgId",
            clientMsgId = clientMsgId,
            sequenceId = tempSeq,
            senderId = senderId,
            body = trimmed,
            createdAt = "Just now",
            isPendingAck = true
        )
        _chatMessages.update { it + optimistic }

        val sentOverWs = _chatWsConnected.value && (activeChatClient?.sendFrame(trimmed, clientMsgId) == true)
        if (!sentOverWs) {
            viewModelScope.launch {
                repository.sendMessageRest(groupId, trimmed, clientMsgId)
                    .onSuccess { res ->
                        _chatMessages.update { list ->
                            list.map { item ->
                                if (item.clientMsgId == clientMsgId) {
                                    item.copy(
                                        messageId = res.messageId,
                                        sequenceId = res.sequenceId,
                                        createdAt = res.createdAt,
                                        isPendingAck = false
                                    )
                                } else {
                                    item
                                }
                            }.distinctBy { it.messageId }.sortedBy { it.sequenceId }
                        }
                    }
                    .onFailure { err ->
                        showBanner(err.message ?: "Message could not be sent", BannerTone.ERROR)
                    }
            }
        }
    }

    fun closeGroupChat() {
        chatCollectJob?.cancel()
        chatCollectJob = null
        activeChatClient?.stop()
        activeChatClient = null
        activeChatGroupId = null
    }

    // --- Matches & Notifications ---

    fun loadMatches() {
        viewModelScope.launch {
            _matchesLoading.value = true
            repository.getMatchInvitations().onSuccess { list ->
                _matchInvitations.value = list
            }
            _matchesLoading.value = false
        }
    }

    fun requestNewMatch(maxDistanceKm: Int = 10, preferredGroupSize: Int = 4) {
        viewModelScope.launch {
            _matchesLoading.value = true
            repository.requestNewMatch(maxDistanceKm, preferredGroupSize)
                .onSuccess { job ->
                    _latestMatchJob.value = job
                    loadMatches()
                    showBanner("Match job ${job.status}: found compatible small-group proposal!", BannerTone.SUCCESS)
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not run matching", BannerTone.ERROR)
                }
            _matchesLoading.value = false
        }
    }

    fun respondToMatchInvitation(invitationId: String, accept: Boolean, onGroupCreated: (String) -> Unit) {
        viewModelScope.launch {
            repository.respondToInvitation(invitationId, accept)
                .onSuccess { res ->
                    loadMatches()
                    loadMyGroups()
                    if (accept && res.groupId != null) {
                        showBanner("All members accepted! Circle formed.", BannerTone.SUCCESS)
                        onGroupCreated(res.groupId)
                    } else if (accept) {
                        showBanner("Invitation accepted — waiting for remaining members.", BannerTone.SUCCESS)
                    } else {
                        showBanner("Invitation declined.", BannerTone.INFO)
                    }
                }
                .onFailure { err ->
                    showBanner(err.message ?: "Could not respond to invitation", BannerTone.ERROR)
                }
        }
    }

    fun loadNotifications() {
        viewModelScope.launch {
            repository.getNotifications().onSuccess { page ->
                _notifications.value = page.items
            }
        }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch {
            repository.markNotificationRead(id)
            loadNotifications()
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            repository.markAllNotificationsRead()
            loadNotifications()
            showBanner("All notifications marked as read", BannerTone.SUCCESS)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeGroupChat()
    }
}
