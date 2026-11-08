package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.CachedDiscoveryPageEntity
import com.example.data.local.DiscoveryCacheDao
import com.example.data.local.RsvpOutboxEntity
import com.example.data.local.RsvpOutboxManager
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.*
import com.example.data.network.GroupChatWebSocketClient
import com.example.data.network.KinshipApiService
import com.example.data.network.TokenRefreshAuthenticator
import com.example.data.network.TokenStore
import com.example.data.network.toKinshipApiException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Central repository layer orchestrating:
 * - Auth & session lifecycle (`TokenStore`, `TokenRefreshAuthenticator`, `RsvpOutboxManager.clearOutbox()`)
 * - Discovery with PostGIS query params, `ETag` / `If-None-Match` 304 conditional GETs, and Room offline cache
 * - Groups, join requests, owner approvals/removals, and recurring series creation
 * - Events & atomic RSVPs with 409 `EVENT_FULL` conflict handling and Room + WorkManager offline outbox
 * - Async Matching (`POST /v1/match-requests`, job status polling, 48h invitations accept/decline)
 * - Notifications polling (`GET /v1/notifications`, mark read, mark all read)
 * - Reviews, reporting, and validated 5 MB image uploads (`jpg/png/webp/gif`)
 * - Seamless fallback to deterministic Berlin seed data when `10.0.2.2:8080` is unreachable in cloud preview
 */
@Singleton
class KinshipRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: KinshipApiService,
    private val tokenStore: TokenStore,
    private val authenticator: TokenRefreshAuthenticator,
    private val discoveryCacheDao: DiscoveryCacheDao,
    private val outboxManager: RsvpOutboxManager,
    private val userPrefsRepo: UserPreferencesRepository,
    private val okHttpClient: OkHttpClient,
    @Named("ApiBaseUrl") private val apiBaseUrl: String,
    private val json: Json
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentUser = MutableStateFlow<UserDto?>(null)
    val currentUser: StateFlow<UserDto?> = _currentUser.asStateFlow()

    private val _authLoading = MutableStateFlow(true)
    val authLoading: StateFlow<Boolean> = _authLoading.asStateFlow()

    private val _isOfflineOrPreview = MutableStateFlow(false)
    val isOfflineOrPreview: StateFlow<Boolean> = _isOfflineOrPreview.asStateFlow()

    private val _unreadNotificationCount = MutableStateFlow(2)
    val unreadNotificationCount: StateFlow<Int> = _unreadNotificationCount.asStateFlow()

    val pendingOutboxFlow: Flow<List<RsvpOutboxEntity>> = outboxManager.observePendingActions()

    // In-memory state for interactive mutations when Docker backend is not reachable
    private val localGroups = mutableMapOf<String, GroupDetailDto>()
    private val localEvents = mutableMapOf<String, EventDetailDto>()
    private val localReviews = mutableMapOf<String, MutableList<ReviewViewDto>>()
    private val localMessages = mutableMapOf<String, MutableList<ChatMessageDto>>()
    private val localInvitations = mutableListOf<MatchInvitationDto>()
    private val localNotifications = mutableListOf<NotificationDto>()

    init {
        seedInteractivePreviewState()
        repoScope.launch {
            restoreSessionOnLaunch()
        }
    }

    private fun seedInteractivePreviewState() {
        DemoSeedCatalog.seededActivities.forEachIndexed { idx, card ->
            val myRole = if (idx == 0) "OWNER" else if (idx == 1) "MEMBER" else null
            val myStatus = if (idx <= 1) "ACTIVE" else null
            val joinPolicy = if (idx == 3) "APPROVAL" else "OPEN"
            val visibility = if (idx == 4) "private" else "public"
            val eventSummary = EventSummaryDto(
                eventId = card.nextEvent.eventId,
                seriesId = "40000000-0000-4000-8000-00000000000${idx + 1}",
                title = "${card.title} Meetup",
                startTime = card.nextEvent.startTime,
                endTime = "2026-11-12T20:30:00Z",
                capacity = card.maxMembers,
                confirmedCount = card.memberCount,
                status = "SCHEDULED",
                myStatus = if (idx == 0) "GOING" else null
            )
            val members = mutableListOf(
                GroupMemberDto(
                    userId = DemoSeedCatalog.demoUser.id,
                    displayName = "Mara Klein",
                    role = if (idx == 0) "OWNER" else "MEMBER",
                    status = "ACTIVE",
                    joinedAt = "2026-11-01T10:00:00Z"
                ),
                GroupMemberDto(
                    userId = "00000000-0000-4000-8000-000000000002",
                    displayName = "Jonas Weber",
                    role = if (idx == 0) "MEMBER" else "OWNER",
                    status = "ACTIVE",
                    joinedAt = "2026-11-01T11:00:00Z"
                ),
                GroupMemberDto(
                    userId = "00000000-0000-4000-8000-000000000003",
                    displayName = "Ana Ruiz",
                    role = "MEMBER",
                    status = "ACTIVE",
                    joinedAt = "2026-11-02T09:30:00Z"
                ),
                GroupMemberDto(
                    userId = "00000000-0000-4000-8000-000000000004",
                    displayName = "Lukas Lindner",
                    role = "MEMBER",
                    status = "ACTIVE",
                    joinedAt = "2026-11-03T14:20:00Z"
                )
            )
            if (idx == 0) {
                members.add(
                    GroupMemberDto(
                        userId = "00000000-0000-4000-8000-000000000005",
                        displayName = "Clara Berg",
                        role = "MEMBER",
                        status = "PENDING",
                        joinedAt = "2026-11-07T16:00:00Z"
                    )
                )
            }
            localGroups[card.groupId] = GroupDetailDto(
                group = GroupDto(
                    groupId = card.groupId,
                    name = card.groupName,
                    description = card.description,
                    category = card.category,
                    activityId = card.activityId,
                    location = card.location,
                    maxMembers = card.maxMembers,
                    visibility = visibility,
                    joinPolicy = joinPolicy,
                    status = "ACTIVE",
                    createdBy = DemoSeedCatalog.demoUser.id,
                    createdAt = "2026-11-01T10:00:00Z",
                    activeMemberCount = card.memberCount,
                    myRole = myRole,
                    myStatus = myStatus
                ),
                members = members,
                nextEvents = listOf(eventSummary)
            )
            localEvents[card.nextEvent.eventId] = EventDetailDto(
                eventId = card.nextEvent.eventId,
                seriesId = eventSummary.seriesId,
                title = eventSummary.title,
                startTime = card.nextEvent.startTime,
                endTime = "2026-11-12T20:30:00Z",
                capacity = card.maxMembers,
                confirmedCount = card.memberCount,
                status = "SCHEDULED",
                myStatus = if (idx == 0) "GOING" else null,
                rsvpCounts = RsvpCountsDto(going = card.memberCount, maybe = 1, declined = 0, notGoing = 0),
                group = GroupRefDto(card.groupId, card.groupName, visibility),
                location = card.location
            )
            localReviews[card.groupId] = mutableListOf(
                ReviewViewDto(
                    id = "50000000-0000-4000-8000-00000000000${idx + 1}",
                    occurrenceId = card.nextEvent.eventId,
                    userId = "00000000-0000-4000-8000-000000000002",
                    overallRating = 5,
                    atmosphereRating = 5,
                    organizationRating = 5,
                    feedback = "Warm, welcoming circle — everyone showed up on time and stayed for tea.",
                    vibeTags = listOf("Welcoming", "Great conversation", "Low pressure"),
                    createdAt = "2026-11-05T21:00:00Z"
                )
            )
            localMessages[card.groupId] = mutableListOf(
                ChatMessageDto(
                    messageId = "60000000-0000-4000-8000-000000000001",
                    sequenceId = 1L,
                    senderId = "00000000-0000-4000-8000-000000000002",
                    body = "Hey everyone! Looking forward to Thursday at 18:30.",
                    createdAt = "2026-11-08T12:15:00Z"
                ),
                ChatMessageDto(
                    messageId = "60000000-0000-4000-8000-000000000002",
                    sequenceId = 2L,
                    senderId = "00000000-0000-4000-8000-000000000003",
                    body = "Me too! I'll grab the corner table near the stage.",
                    createdAt = "2026-11-08T12:18:00Z"
                )
            )
        }

        localInvitations.add(
            MatchInvitationDto(
                invitationId = "70000000-0000-4000-8000-000000000001",
                matchGroupId = "71000000-0000-4000-8000-000000000001",
                expiresAt = "2026-11-10T18:00:00Z",
                status = "INVITED",
                members = listOf(
                    MemberPreviewDto(
                        userId = DemoSeedCatalog.demoUser.id,
                        displayName = "Mara Klein (You)",
                        interests = listOf("Live Music", "Bouldering", "Specialty Coffee"),
                        distanceKm = 0.0
                    ),
                    MemberPreviewDto(
                        userId = "00000000-0000-4000-8000-000000000006",
                        displayName = "Soren Lindqvist",
                        interests = listOf("Live Music", "Bouldering"),
                        distanceKm = 1.4
                    ),
                    MemberPreviewDto(
                        userId = "00000000-0000-4000-8000-000000000007",
                        displayName = "Elena Rostova",
                        interests = listOf("Bouldering", "Specialty Coffee"),
                        distanceKm = 2.1
                    ),
                    MemberPreviewDto(
                        userId = "00000000-0000-4000-8000-000000000008",
                        displayName = "Tariq Mansoor",
                        interests = listOf("Live Music", "Indie Book Club"),
                        distanceKm = 2.8
                    )
                )
            )
        )

        localNotifications.addAll(
            listOf(
                NotificationDto(
                    id = "80000000-0000-4000-8000-000000000001",
                    type = "NEW_MESSAGE",
                    title = "New message in Live Music Night #2 Crew",
                    body = "Ana Ruiz: Me too! I'll grab the corner table near the stage.",
                    entityType = "GROUP",
                    entityId = "20000000-0000-4000-8000-000000000001",
                    readAt = null,
                    createdAt = "2026-11-08T12:18:00Z"
                ),
                NotificationDto(
                    id = "80000000-0000-4000-8000-000000000002",
                    type = "JOIN_REQUEST",
                    title = "Clara Berg requested to join Live Music Night #2 Crew",
                    body = "Review pending membership request in your circle.",
                    entityType = "GROUP",
                    entityId = "20000000-0000-4000-8000-000000000001",
                    readAt = null,
                    createdAt = "2026-11-08T09:40:00Z"
                ),
                NotificationDto(
                    id = "80000000-0000-4000-8000-000000000003",
                    type = "EVENT_REMINDER",
                    title = "Upcoming meetup: Live Music Night #2",
                    body = "Thursday at 18:30 · 5/6 seats confirmed.",
                    entityType = "EVENT",
                    entityId = "30000000-0000-4000-8000-000000000001",
                    readAt = "2026-11-07T20:00:00Z",
                    createdAt = "2026-11-07T18:30:00Z"
                )
            )
        )
    }

    suspend fun restoreSessionOnLaunch() {
        _authLoading.value = true
        try {
            val prefs = userPrefsRepo.prefsFlow.first()
            if (!prefs.cachedUserJson.isNullOrBlank()) {
                runCatching {
                    _currentUser.value = json.decodeFromString<UserDto>(prefs.cachedUserJson)
                }
            }
            val refreshedAccess = authenticator.refreshSessionSingleFlight()
            if (!refreshedAccess.isNullOrBlank()) {
                val me = apiService.getMe()
                _currentUser.value = me
                _isOfflineOrPreview.value = false
                userPrefsRepo.saveCachedUserJson(json.encodeToString(me))
                outboxManager.scheduleSyncWork(context)
            } else if (_currentUser.value == null) {
                // Check if backend is reachable; if not, sign in demo organizer for instant preview
                try {
                    apiService.getInterests()
                    _isOfflineOrPreview.value = false
                } catch (_: Exception) {
                    _isOfflineOrPreview.value = true
                    _currentUser.value = DemoSeedCatalog.demoUser
                    tokenStore.setAccessToken("demo_preview_jwt_token")
                }
            }
        } catch (_: Exception) {
            _isOfflineOrPreview.value = true
            if (_currentUser.value == null) {
                _currentUser.value = DemoSeedCatalog.demoUser
                tokenStore.setAccessToken("demo_preview_jwt_token")
            }
        } finally {
            _authLoading.value = false
        }
    }

    suspend fun login(email: String, password: String): Result<UserDto> {
        return try {
            purgeSessionData()
            val response = apiService.login(LoginRequest(email.trim(), password))
            tokenStore.setAccessToken(response.accessToken)
            _currentUser.value = response.user
            _isOfflineOrPreview.value = false
            userPrefsRepo.saveCachedUserJson(json.encodeToString(response.user))
            Result.success(response.user)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                // Backend unreachable in emulator preview -> allow demo@kinship.local or any valid credentials
                _isOfflineOrPreview.value = true
                val user = DemoSeedCatalog.demoUser.copy(
                    email = email.trim().lowercase(),
                    displayName = if (email.startsWith("demo")) "Mara Klein" else email.substringBefore("@").replaceFirstChar { it.uppercase() }
                )
                tokenStore.setAccessToken("demo_preview_jwt_token")
                _currentUser.value = user
                userPrefsRepo.saveCachedUserJson(json.encodeToString(user))
                Result.success(user)
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun register(displayName: String, email: String, password: String, timezone: String = "Europe/Berlin"): Result<UserDto> {
        return try {
            purgeSessionData()
            val response = apiService.register(
                RegisterRequest(
                    email = email.trim(),
                    password = password,
                    displayName = displayName.trim(),
                    timezone = timezone
                )
            )
            tokenStore.setAccessToken(response.accessToken)
            _currentUser.value = response.user
            _isOfflineOrPreview.value = false
            userPrefsRepo.saveCachedUserJson(json.encodeToString(response.user))
            Result.success(response.user)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                _isOfflineOrPreview.value = true
                val newUser = DemoSeedCatalog.demoUser.copy(
                    id = UUID.randomUUID().toString(),
                    email = email.trim().lowercase(),
                    displayName = displayName.trim(),
                    onboardingCompleted = false,
                    onboardingStep = 1
                )
                tokenStore.setAccessToken("demo_preview_jwt_token")
                _currentUser.value = newUser
                userPrefsRepo.saveCachedUserJson(json.encodeToString(newUser))
                Result.success(newUser)
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun logout() {
        try {
            apiService.logout()
        } catch (_: Exception) {
            // Ignore network errors during logout
        } finally {
            purgeSessionData()
            _currentUser.value = null
        }
    }

    private suspend fun purgeSessionData() {
        tokenStore.clearAll()
        outboxManager.clearOutbox()
        discoveryCacheDao.clearAll()
        userPrefsRepo.clearSessionCache()
    }

    suspend fun refreshProfile(): Result<UserDto> {
        return try {
            val me = apiService.getMe()
            _currentUser.value = me
            _isOfflineOrPreview.value = false
            userPrefsRepo.saveCachedUserJson(json.encodeToString(me))
            Result.success(me)
        } catch (t: Throwable) {
            val current = _currentUser.value
            if (current != null) Result.success(current)
            else Result.failure(t.toKinshipApiException())
        }
    }

    suspend fun updateProfile(request: UpdateProfileRequest): Result<UserDto> {
        return try {
            val updated = apiService.updateMe(request)
            _currentUser.value = updated
            _isOfflineOrPreview.value = false
            userPrefsRepo.saveCachedUserJson(json.encodeToString(updated))
            Result.success(updated)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                _isOfflineOrPreview.value = true
                val base = _currentUser.value ?: DemoSeedCatalog.demoUser
                val mappedInterests = request.interests?.map { sel ->
                    val tax = DemoSeedCatalog.taxonomyInterests.find { it.id == sel.interestId }
                    UserInterestDto(
                        interestId = sel.interestId,
                        slug = tax?.slug ?: "interest-${sel.interestId}",
                        name = tax?.name ?: "Interest #${sel.interestId}",
                        weight = sel.weight
                    )
                } ?: base.interests
                val updated = base.copy(
                    displayName = request.displayName ?: base.displayName,
                    location = request.location ?: base.location,
                    availability = request.availability ?: base.availability,
                    interests = mappedInterests,
                    bio = request.bio ?: base.bio,
                    hobbies = request.hobbies ?: base.hobbies,
                    profession = request.profession ?: base.profession,
                    workplace = request.workplace ?: base.workplace,
                    workplaceShared = request.workplaceShared ?: base.workplaceShared,
                    profilePhotoAssetId = request.profilePhotoAssetId ?: base.profilePhotoAssetId,
                    onboardingCompleted = request.onboardingCompleted ?: base.onboardingCompleted
                )
                _currentUser.value = updated
                userPrefsRepo.saveCachedUserJson(json.encodeToString(updated))
                Result.success(updated)
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun getInterests(): List<InterestResponseDto> {
        return try {
            apiService.getInterests().items.ifEmpty { DemoSeedCatalog.taxonomyInterests }
        } catch (_: Exception) {
            DemoSeedCatalog.taxonomyInterests
        }
    }

    /**
     * Performs conditional GET `/v1/activities` with `If-None-Match: <etag>` and caches
     * the page in Room (`cached_discovery_pages`). On `304 Not Modified` or offline error,
     * returns the cached Room page.
     */
    suspend fun discoverActivities(
        lat: Double,
        lng: Double,
        radiusKm: Int,
        category: String?,
        groupSizeMax: Int?,
        pageToken: String? = null
    ): Result<DiscoveryPageDto> {
        val normalizedCategory = category?.takeIf { it.isNotBlank() }
        val queryKey = "lat=${"%.4f".format(lat)}&lng=${"%.4f".format(lng)}&r=$radiusKm&cat=${normalizedCategory.orEmpty()}&sz=${groupSizeMax ?: ""}&pt=${pageToken.orEmpty()}"
        val cachedEntity = discoveryCacheDao.getByQueryKey(queryKey)

        return try {
            val response = apiService.discoverActivities(
                lat = lat,
                lng = lng,
                radiusKm = radiusKm.toDouble(),
                category = normalizedCategory,
                groupSizeMax = groupSizeMax,
                pageToken = pageToken,
                limit = 12,
                ifNoneMatch = cachedEntity?.etag
            )
            if (response.code() == 304 && cachedEntity != null) {
                _isOfflineOrPreview.value = false
                val page = json.decodeFromString<DiscoveryPageDto>(cachedEntity.payloadJson)
                return Result.success(page)
            }
            if (response.isSuccessful) {
                val body = response.body() ?: DiscoveryPageDto()
                val newEtag = response.headers()["ETag"] ?: cachedEntity?.etag
                discoveryCacheDao.upsertPage(
                    CachedDiscoveryPageEntity(
                        queryKey = queryKey,
                        etag = newEtag,
                        payloadJson = json.encodeToString(body)
                    )
                )
                _isOfflineOrPreview.value = false
                return Result.success(body)
            }
            throw KinshipApiException(response.code(), "DISCOVERY_FAILED", "Could not load activities")
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (cachedEntity != null) {
                _isOfflineOrPreview.value = true
                val page = json.decodeFromString<DiscoveryPageDto>(cachedEntity.payloadJson)
                return Result.success(page)
            }
            if (apiEx.status == 0) {
                _isOfflineOrPreview.value = true
                val filtered = DemoSeedCatalog.seededActivities.filter { card ->
                    (normalizedCategory == null || card.category.equals(normalizedCategory, ignoreCase = true)) &&
                        (groupSizeMax == null || card.maxMembers <= groupSizeMax) &&
                        (card.distanceM <= radiusKm * 1000)
                }
                val page = DiscoveryPageDto(items = filtered, nextPageToken = null)
                discoveryCacheDao.upsertPage(
                    CachedDiscoveryPageEntity(
                        queryKey = queryKey,
                        etag = "\"seed-etag-${queryKey.hashCode()}\"",
                        payloadJson = json.encodeToString(page)
                    )
                )
                return Result.success(page)
            }
            Result.failure(apiEx)
        }
    }

    suspend fun getActivityDetail(activityId: String): Result<ActivityDetailDto> {
        return try {
            val detail = apiService.getActivityDetail(activityId)
            _isOfflineOrPreview.value = false
            Result.success(detail)
        } catch (t: Throwable) {
            val card = DemoSeedCatalog.seededActivities.find { it.activityId == activityId }
                ?: DemoSeedCatalog.seededActivities.first()
            val groupDetail = localGroups[card.groupId]
            Result.success(
                ActivityDetailDto(
                    activityId = card.activityId,
                    title = card.title,
                    description = card.description,
                    category = card.category,
                    location = card.location,
                    organization = OrganizationSummaryDto(
                        id = "90000000-0000-4000-8000-000000000001",
                        name = "Berlin Neighborhood Collective",
                        type = "COMMUNITY"
                    ),
                    groups = listOf(
                        GroupSummaryDto(
                            groupId = card.groupId,
                            name = card.groupName,
                            visibility = groupDetail?.group?.visibility ?: "public",
                            memberCount = groupDetail?.group?.activeMemberCount ?: card.memberCount,
                            maxMembers = card.maxMembers,
                            nextEvent = card.nextEvent
                        )
                    )
                )
            )
        }
    }

    suspend fun getMyGroups(pageToken: String? = null): Result<GroupPageDto> {
        return try {
            val page = apiService.getMyGroups(mine = true, pageToken = pageToken, limit = 20)
            _isOfflineOrPreview.value = false
            Result.success(page)
        } catch (_: Exception) {
            val items = localGroups.values
                .filter { it.group.myStatus == "ACTIVE" || it.group.myStatus == "PENDING" }
                .map { detail ->
                    GroupCardDto(
                        groupId = detail.group.groupId,
                        name = detail.group.name,
                        category = detail.group.category,
                        visibility = detail.group.visibility,
                        maxMembers = detail.group.maxMembers,
                        memberCount = detail.group.activeMemberCount,
                        myStatus = detail.group.myStatus ?: "ACTIVE",
                        nextEventId = detail.nextEvents.firstOrNull()?.eventId,
                        nextEventAt = detail.nextEvents.firstOrNull()?.startTime
                    )
                }
            Result.success(GroupPageDto(items = items, nextPageToken = null))
        }
    }

    suspend fun createGroup(request: CreateGroupRequest): Result<GroupDetailDto> {
        return try {
            val created = apiService.createGroup(request)
            Result.success(created)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val id = UUID.randomUUID().toString()
                val user = _currentUser.value ?: DemoSeedCatalog.demoUser
                val loc = request.location ?: LocationDto(52.5200, 13.4050, "Berlin")
                val groupDto = GroupDto(
                    groupId = id,
                    name = request.name,
                    description = request.description,
                    category = request.category,
                    activityId = request.activityId,
                    location = loc,
                    maxMembers = request.maxMembers,
                    visibility = request.visibility,
                    joinPolicy = request.joinPolicy,
                    status = "ACTIVE",
                    createdBy = user.id,
                    createdAt = "2026-11-08T12:00:00Z",
                    activeMemberCount = 1,
                    myRole = "OWNER",
                    myStatus = "ACTIVE"
                )
                val detail = GroupDetailDto(
                    group = groupDto,
                    members = listOf(
                        GroupMemberDto(
                            userId = user.id,
                            displayName = user.displayName,
                            role = "OWNER",
                            status = "ACTIVE",
                            joinedAt = "2026-11-08T12:00:00Z"
                        )
                    ),
                    nextEvents = emptyList()
                )
                localGroups[id] = detail
                Result.success(detail)
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun getGroupDetail(groupId: String): Result<GroupDetailDto> {
        return try {
            val detail = apiService.getGroupDetail(groupId)
            localGroups[groupId] = detail
            Result.success(detail)
        } catch (t: Throwable) {
            val local = localGroups[groupId]
            if (local != null) Result.success(local)
            else Result.failure(t.toKinshipApiException())
        }
    }

    suspend fun joinGroup(groupId: String): Result<JoinResponseDto> {
        return try {
            val res = apiService.joinGroup(groupId)
            Result.success(res)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val existing = localGroups[groupId] ?: return Result.failure(apiEx)
                val status = if (existing.group.visibility == "private" || existing.group.joinPolicy == "APPROVAL") "PENDING" else "ACTIVE"
                val user = _currentUser.value ?: DemoSeedCatalog.demoUser
                val updatedMembers = existing.members.filterNot { it.userId == user.id } +
                    GroupMemberDto(user.id, user.displayName, "MEMBER", status, "2026-11-08T12:00:00Z")
                localGroups[groupId] = existing.copy(
                    group = existing.group.copy(
                        myRole = "MEMBER",
                        myStatus = status,
                        activeMemberCount = updatedMembers.count { it.status == "ACTIVE" }
                    ),
                    members = updatedMembers
                )
                Result.success(JoinResponseDto(groupId = groupId, status = status))
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun requestJoinGroup(groupId: String, reason: String, shareWorkplace: Boolean): Result<JoinRequestResponseDto> {
        return try {
            val res = apiService.requestJoinGroup(groupId, JoinRequestCreateDto(reason, shareWorkplace))
            Result.success(res)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val existing = localGroups[groupId]
                val user = _currentUser.value ?: DemoSeedCatalog.demoUser
                if (existing != null) {
                    val updatedMembers = existing.members.filterNot { it.userId == user.id } +
                        GroupMemberDto(user.id, user.displayName, "MEMBER", "PENDING", "2026-11-08T12:00:00Z")
                    localGroups[groupId] = existing.copy(
                        group = existing.group.copy(myRole = "MEMBER", myStatus = "PENDING"),
                        members = updatedMembers
                    )
                }
                Result.success(JoinRequestResponseDto(UUID.randomUUID().toString(), groupId, "PENDING"))
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun leaveGroup(groupId: String): Result<Unit> {
        return try {
            apiService.leaveGroup(groupId)
            Result.success(Unit)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val existing = localGroups[groupId]
                val user = _currentUser.value ?: DemoSeedCatalog.demoUser
                if (existing != null) {
                    val updatedMembers = existing.members.filterNot { it.userId == user.id }
                    localGroups[groupId] = existing.copy(
                        group = existing.group.copy(
                            myRole = null,
                            myStatus = null,
                            activeMemberCount = updatedMembers.count { it.status == "ACTIVE" }
                        ),
                        members = updatedMembers
                    )
                }
                Result.success(Unit)
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun updateMemberStatus(groupId: String, userId: String, status: String): Result<Unit> {
        return try {
            apiService.updateMemberStatus(groupId, userId, MembershipUpdateRequest(status))
            Result.success(Unit)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val existing = localGroups[groupId]
                if (existing != null) {
                    val updatedMembers = if (status == "REMOVED") {
                        existing.members.filterNot { it.userId == userId }
                    } else {
                        existing.members.map { if (it.userId == userId) it.copy(status = "ACTIVE") else it }
                    }
                    localGroups[groupId] = existing.copy(
                        group = existing.group.copy(activeMemberCount = updatedMembers.count { it.status == "ACTIVE" }),
                        members = updatedMembers
                    )
                }
                Result.success(Unit)
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun createEventSeries(groupId: String, request: CreateSeriesRequest): Result<CreateSeriesResponseDto> {
        return try {
            val created = apiService.createEventSeries(groupId, request)
            Result.success(created)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val seriesId = UUID.randomUUID().toString()
                val existing = localGroups[groupId]
                val cap = request.capacity ?: existing?.group?.maxMembers ?: 6
                val occurrences = (0 until 4).map { week ->
                    val evId = UUID.randomUUID().toString()
                    val startIso = "${request.recurrence.startsOn}T${request.recurrence.startTime}:00Z"
                    val endIso = "${request.recurrence.startsOn}T20:00:00Z"
                    localEvents[evId] = EventDetailDto(
                        eventId = evId,
                        seriesId = seriesId,
                        title = request.title,
                        startTime = startIso,
                        endTime = endIso,
                        capacity = cap,
                        confirmedCount = 1,
                        status = "SCHEDULED",
                        myStatus = "GOING",
                        rsvpCounts = RsvpCountsDto(going = 1, maybe = 0, declined = 0, notGoing = 0),
                        group = GroupRefDto(groupId, existing?.group?.name ?: "Group", existing?.group?.visibility ?: "public"),
                        location = existing?.group?.location ?: LocationDto(52.52, 13.405, "Berlin")
                    )
                    OccurrenceResponseDto(evId, startIso, endIso)
                }
                if (existing != null) {
                    val newSummary = EventSummaryDto(
                        eventId = occurrences.first().eventId,
                        seriesId = seriesId,
                        title = request.title,
                        startTime = occurrences.first().startTime,
                        endTime = occurrences.first().endTime,
                        capacity = cap,
                        confirmedCount = 1,
                        status = "SCHEDULED",
                        myStatus = "GOING"
                    )
                    localGroups[groupId] = existing.copy(nextEvents = listOf(newSummary) + existing.nextEvents)
                }
                Result.success(CreateSeriesResponseDto(seriesId, "2027-01-03T00:00:00Z", occurrences))
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun getEventDetail(eventId: String): Result<EventDetailDto> {
        return try {
            val detail = apiService.getEventDetail(eventId)
            localEvents[eventId] = detail
            Result.success(detail)
        } catch (t: Throwable) {
            val local = localEvents[eventId]
            if (local != null) Result.success(local)
            else Result.failure(t.toKinshipApiException())
        }
    }

    /**
     * Submits an RSVP (`GOING`, `MAYBE`, `NOT_GOING`) for an occurrence.
     * - Handles `409 EVENT_FULL` capacity conflict with a clear user-facing error message.
     * - When offline (`status == 0`), queues the RSVP into the Room Outbox (`RsvpOutboxManager`)
     *   and schedules a `WorkManager` job (`RsvpOutboxSyncWorker`) to flush on reconnect.
     */
    suspend fun submitRsvp(eventId: String, status: String, forceQueueOffline: Boolean = false): Result<RsvpSubmissionOutcome> {
        if (forceQueueOffline) {
            outboxManager.queueRsvp(eventId, status)
            outboxManager.scheduleSyncWork(context)
            return Result.success(RsvpSubmissionOutcome.QueuedOffline(status))
        }
        return try {
            val response = apiService.submitRsvp(eventId, RsvpRequestDto(status))
            updateLocalEventRsvp(eventId, status, response.confirmedCount, response.capacity)
            Result.success(RsvpSubmissionOutcome.Synced(response))
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 409 && apiEx.code == "EVENT_FULL") {
                return Result.failure(
                    KinshipApiException(
                        409,
                        "EVENT_FULL",
                        "This occurrence just filled up (409 capacity conflict). Try another upcoming date."
                    )
                )
            }
            if (apiEx.status == 0) {
                // Check local capacity conflict simulation when testing capacity edge cases
                val localEv = localEvents[eventId]
                if (localEv != null && status == "GOING" && localEv.myStatus != "GOING" && localEv.confirmedCount >= localEv.capacity) {
                    return Result.failure(
                        KinshipApiException(
                            409,
                            "EVENT_FULL",
                            "This occurrence just filled up (409 capacity conflict). Try another upcoming date."
                        )
                    )
                }
                // Queue in Room outbox + update local preview state
                outboxManager.queueRsvp(eventId, status)
                outboxManager.scheduleSyncWork(context)
                if (localEv != null) {
                    val delta = (if (status == "GOING") 1 else 0) - (if (localEv.myStatus == "GOING") 1 else 0)
                    val newConfirmed = (localEv.confirmedCount + delta).coerceIn(0, localEv.capacity)
                    updateLocalEventRsvp(eventId, status, newConfirmed, localEv.capacity)
                }
                return Result.success(RsvpSubmissionOutcome.QueuedOffline(status))
            }
            Result.failure(apiEx)
        }
    }

    private fun updateLocalEventRsvp(eventId: String, status: String, confirmedCount: Int, capacity: Int) {
        val current = localEvents[eventId] ?: return
        val oldStatus = current.myStatus
        val maybeDelta = (if (status == "MAYBE") 1 else 0) - (if (oldStatus == "MAYBE") 1 else 0)
        val notGoingDelta = (if (status == "NOT_GOING") 1 else 0) - (if (oldStatus == "NOT_GOING") 1 else 0)
        localEvents[eventId] = current.copy(
            myStatus = status,
            confirmedCount = confirmedCount,
            capacity = capacity,
            rsvpCounts = current.rsvpCounts.copy(
                going = confirmedCount,
                maybe = (current.rsvpCounts.maybe + maybeDelta).coerceAtLeast(0),
                notGoing = (current.rsvpCounts.notGoing + notGoingDelta).coerceAtLeast(0)
            )
        )
    }

    suspend fun getGroupReviews(groupId: String): ReviewSummaryDto {
        return try {
            apiService.getGroupReviews(groupId)
        } catch (_: Exception) {
            val list = localReviews[groupId].orEmpty()
            if (list.isEmpty()) {
                ReviewSummaryDto(count = 0)
            } else {
                ReviewSummaryDto(
                    count = list.size,
                    avgOverall = list.map { it.overallRating }.average(),
                    avgAtmosphere = list.map { it.atmosphereRating }.average(),
                    avgOrganization = list.map { it.organizationRating }.average(),
                    recent = list
                )
            }
        }
    }

    suspend fun submitReview(occurrenceId: String, groupId: String, request: ReviewRequestDto): Result<ReviewViewDto> {
        return try {
            val view = apiService.submitReview(occurrenceId, request)
            Result.success(view)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val user = _currentUser.value ?: DemoSeedCatalog.demoUser
                val view = ReviewViewDto(
                    id = UUID.randomUUID().toString(),
                    occurrenceId = occurrenceId,
                    userId = user.id,
                    overallRating = request.overallRating,
                    atmosphereRating = request.atmosphereRating,
                    organizationRating = request.organizationRating,
                    feedback = request.feedback,
                    vibeTags = request.vibeTags,
                    createdAt = "2026-11-08T15:00:00Z"
                )
                val list = localReviews.getOrPut(groupId) { mutableListOf() }
                list.add(0, view)
                Result.success(view)
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun reportReview(reviewId: String, reason: String): Result<Unit> {
        return try {
            apiService.reportReview(reviewId, ReportReviewRequestDto(reason))
            Result.success(Unit)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) Result.success(Unit)
            else Result.failure(apiEx)
        }
    }

    suspend fun getMatchInvitations(): Result<List<MatchInvitationDto>> {
        return try {
            val res = apiService.getMatchInvitations("INVITED")
            Result.success(res.items)
        } catch (_: Exception) {
            Result.success(localInvitations.toList())
        }
    }

    suspend fun requestNewMatch(maxDistanceKm: Int = 10, preferredGroupSize: Int = 4): Result<MatchJobResponseDto> {
        return try {
            val created = apiService.createMatchRequest(MatchRequestDto(maxDistanceKm, preferredGroupSize))
            val job = apiService.getMatchJob(created.jobId)
            Result.success(job)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val user = _currentUser.value ?: DemoSeedCatalog.demoUser
                if (user.location == null || user.interests.size < 2 || user.availability.isEmpty()) {
                    return Result.failure(
                        KinshipApiException(
                            422,
                            "PROFILE_INCOMPLETE",
                            "Add a location, at least two interests and availability to your profile before matching."
                        )
                    )
                }
                val newInvite = MatchInvitationDto(
                    invitationId = UUID.randomUUID().toString(),
                    matchGroupId = UUID.randomUUID().toString(),
                    expiresAt = "2026-11-10T19:30:00Z",
                    status = "INVITED",
                    members = listOf(
                        MemberPreviewDto(user.id, "${user.displayName} (You)", user.interests.map { it.name }, 0.0),
                        MemberPreviewDto(UUID.randomUUID().toString(), "Hannah Richter", listOf("Bouldering", "Live Music"), 1.2),
                        MemberPreviewDto(UUID.randomUUID().toString(), "Matteo Rossi", listOf("Live Music", "Specialty Coffee"), 1.9),
                        MemberPreviewDto(UUID.randomUUID().toString(), "Nora Lind", listOf("Bouldering", "Canal Running"), 2.4)
                    )
                )
                if (localInvitations.none { it.invitationId == newInvite.invitationId }) {
                    localInvitations.add(0, newInvite)
                }
                Result.success(
                    MatchJobResponseDto(
                        jobId = UUID.randomUUID().toString(),
                        status = "DONE",
                        results = listOf(
                            MatchResultDto(
                                matchGroupId = newInvite.matchGroupId,
                                status = "INVITED",
                                score = 0.86,
                                expiresAt = newInvite.expiresAt,
                                members = newInvite.members
                            )
                        )
                    )
                )
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun respondToInvitation(invitationId: String, accept: Boolean): Result<AcceptResponseDto> {
        return try {
            if (accept) {
                val res = apiService.acceptInvitation(invitationId)
                Result.success(res)
            } else {
                val res = apiService.declineInvitation(invitationId)
                Result.success(AcceptResponseDto(status = res.status, groupId = null))
            }
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val invite = localInvitations.find { it.invitationId == invitationId }
                localInvitations.removeAll { it.invitationId == invitationId }
                if (accept && invite != null) {
                    val createdGroupId = UUID.randomUUID().toString()
                    val user = _currentUser.value ?: DemoSeedCatalog.demoUser
                    localGroups[createdGroupId] = GroupDetailDto(
                        group = GroupDto(
                            groupId = createdGroupId,
                            name = "Matched Circle · Live Music & Bouldering",
                            description = "Formed from your 48-hour Kinship match proposal.",
                            category = "music",
                            location = user.location ?: LocationDto(52.52, 13.405, "Berlin"),
                            maxMembers = 4,
                            visibility = "private",
                            joinPolicy = "OPEN",
                            status = "ACTIVE",
                            createdBy = user.id,
                            activeMemberCount = 4,
                            myRole = "OWNER",
                            myStatus = "ACTIVE"
                        ),
                        members = invite.members.mapIndexed { idx, m ->
                            GroupMemberDto(
                                userId = m.userId,
                                displayName = m.displayName.removeSuffix(" (You)"),
                                role = if (idx == 0) "OWNER" else "MEMBER",
                                status = "ACTIVE"
                            )
                        },
                        nextEvents = emptyList()
                    )
                    Result.success(AcceptResponseDto(status = "ACCEPTED", groupId = createdGroupId))
                } else {
                    Result.success(AcceptResponseDto(status = "DECLINED", groupId = null))
                }
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun getNotifications(): Result<NotificationPageDto> {
        return try {
            val page = apiService.getNotifications()
            _unreadNotificationCount.value = page.unreadCount
            Result.success(page)
        } catch (_: Exception) {
            val unread = localNotifications.count { it.readAt == null }
            _unreadNotificationCount.value = unread
            Result.success(NotificationPageDto(items = localNotifications.toList(), unreadCount = unread))
        }
    }

    suspend fun markNotificationRead(id: String) {
        try {
            apiService.markNotificationRead(id)
        } catch (_: Exception) {
        }
        val idx = localNotifications.indexOfFirst { it.id == id }
        if (idx >= 0) {
            localNotifications[idx] = localNotifications[idx].copy(readAt = "2026-11-08T15:00:00Z")
        }
        _unreadNotificationCount.value = localNotifications.count { it.readAt == null }
    }

    suspend fun markAllNotificationsRead() {
        try {
            apiService.markAllNotificationsRead()
        } catch (_: Exception) {
        }
        for (i in localNotifications.indices) {
            if (localNotifications[i].readAt == null) {
                localNotifications[i] = localNotifications[i].copy(readAt = "2026-11-08T15:00:00Z")
            }
        }
        _unreadNotificationCount.value = 0
    }

    /**
     * Validates and uploads an image (`PROFILE`, `GROUP_BANNER`, or `REVIEW`) enforcing
     * the backend's 5 MB max file size and allowed MIME types (`image/jpeg`, `image/png`, `image/webp`, `image/gif`).
     */
    suspend fun uploadImageUri(uri: Uri, type: String = "PROFILE", groupId: String? = null): Result<String> {
        return try {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)?.lowercase() ?: "image/jpeg"
            val allowed = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
            if (mimeType !in allowed) {
                return Result.failure(
                    KinshipApiException(400, "INVALID_FILE_TYPE", "Only JPG, PNG, WebP, or GIF images are supported.")
                )
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(KinshipApiException(400, "VALIDATION_ERROR", "Unable to read selected image."))
            if (bytes.size > 5 * 1024 * 1024) {
                return Result.failure(
                    KinshipApiException(400, "FILE_TOO_LARGE", "Image exceeds the 5 MB maximum size limit.")
                )
            }
            val ext = when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", "upload.$ext", requestBody)
            val response = apiService.uploadImage(type = type, groupId = groupId, file = part)
            Result.success(response.imageId)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                Result.success(UUID.randomUUID().toString())
            } else {
                Result.failure(apiEx)
            }
        }
    }

    suspend fun getMessageHistory(groupId: String, beforeSeq: Long? = null): Result<MessagePageDto> {
        return try {
            val page = apiService.getMessages(groupId, beforeSeq = beforeSeq, limit = 50)
            Result.success(page)
        } catch (_: Exception) {
            val list = localMessages[groupId].orEmpty()
            Result.success(MessagePageDto(items = list, nextBeforeSeq = null))
        }
    }

    suspend fun sendMessageRest(groupId: String, body: String, clientMsgId: String): Result<SendMessageResponseDto> {
        return try {
            val res = apiService.sendMessage(groupId, SendMessageRequestDto(body = body, clientMsgId = clientMsgId))
            Result.success(res)
        } catch (t: Throwable) {
            val apiEx = t.toKinshipApiException()
            if (apiEx.status == 0) {
                val user = _currentUser.value ?: DemoSeedCatalog.demoUser
                val list = localMessages.getOrPut(groupId) { mutableListOf() }
                val nextSeq = (list.maxOfOrNull { it.sequenceId } ?: 0L) + 1L
                val msgId = UUID.randomUUID().toString()
                val nowIso = "2026-11-08T15:30:00Z"
                val msg = ChatMessageDto(
                    messageId = msgId,
                    sequenceId = nextSeq,
                    senderId = user.id,
                    body = body,
                    createdAt = nowIso
                )
                list.add(msg)
                Result.success(SendMessageResponseDto(msgId, nextSeq, nowIso))
            } else {
                Result.failure(apiEx)
            }
        }
    }

    fun createWebSocketChatClient(groupId: String, scope: CoroutineScope): GroupChatWebSocketClient {
        return GroupChatWebSocketClient(
            groupId = groupId,
            baseUrlProvider = { apiBaseUrl },
            okHttpClient = okHttpClient,
            apiService = apiService,
            externalScope = scope,
            json = json
        )
    }
}

sealed class RsvpSubmissionOutcome {
    data class Synced(val response: RsvpResponseDto) : RsvpSubmissionOutcome()
    data class QueuedOffline(val status: String) : RsvpSubmissionOutcome()
}
