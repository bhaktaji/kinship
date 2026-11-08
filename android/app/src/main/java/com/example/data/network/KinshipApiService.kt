package com.example.data.network

import com.example.data.model.*
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.*

interface KinshipApiService {

    // 1. Authentication
    @POST("/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("/v1/auth/refresh")
    suspend fun refresh(): TokenResponse

    @POST("/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    // 2. Users & Taxonomy
    @GET("/v1/users/me")
    suspend fun getMe(): UserDto

    @PATCH("/v1/users/me")
    suspend fun updateMe(@Body request: UpdateProfileRequest): UserDto

    @GET("/v1/interests")
    suspend fun getInterests(): InterestsEnvelope

    // 3. Discovery & Activities
    @GET("/v1/activities")
    suspend fun discoverActivities(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius_km") radiusKm: Double = 10.0,
        @Query("category") category: String? = null,
        @Query("starts_after") startsAfter: String? = null,
        @Query("group_size_max") groupSizeMax: Int? = null,
        @Query("page_token") pageToken: String? = null,
        @Query("limit") limit: Int = 20,
        @Header("If-None-Match") ifNoneMatch: String? = null
    ): Response<DiscoveryPageDto>

    @GET("/v1/activities/{id}")
    suspend fun getActivityDetail(@Path("id") id: String): ActivityDetailDto

    // 4. Groups & Membership
    @GET("/v1/groups")
    suspend fun getMyGroups(
        @Query("mine") mine: Boolean = true,
        @Query("page_token") pageToken: String? = null,
        @Query("limit") limit: Int = 20
    ): GroupPageDto

    @POST("/v1/groups")
    suspend fun createGroup(@Body request: CreateGroupRequest): GroupDetailDto

    @GET("/v1/groups/{id}")
    suspend fun getGroupDetail(@Path("id") id: String): GroupDetailDto

    @PATCH("/v1/groups/{id}")
    suspend fun updateGroup(
        @Path("id") id: String,
        @Body request: UpdateGroupRequest
    ): GroupDto

    @POST("/v1/groups/{id}/join")
    suspend fun joinGroup(@Path("id") id: String): JoinResponseDto

    @POST("/v1/groups/{id}/join-requests")
    suspend fun requestJoinGroup(
        @Path("id") id: String,
        @Body request: JoinRequestCreateDto
    ): JoinRequestResponseDto

    @GET("/v1/groups/{id}/join-requests")
    suspend fun listJoinRequests(@Path("id") id: String): List<JoinRequestViewDto>

    @POST("/v1/groups/join-requests/{requestId}/approve")
    suspend fun approveJoinRequest(@Path("requestId") requestId: String): Response<Unit>

    @POST("/v1/groups/join-requests/{requestId}/reject")
    suspend fun rejectJoinRequest(@Path("requestId") requestId: String): Response<Unit>

    @DELETE("/v1/groups/{id}/members/me")
    suspend fun leaveGroup(@Path("id") id: String): Response<Unit>

    @PATCH("/v1/groups/{id}/members/{userId}")
    suspend fun updateMemberStatus(
        @Path("id") groupId: String,
        @Path("userId") userId: String,
        @Body request: MembershipUpdateRequest
    ): Response<Unit>

    // 5. Events & Series & RSVP
    @GET("/v1/groups/{groupId}/events")
    suspend fun listGroupEvents(
        @Path("groupId") groupId: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("limit") limit: Int = 50
    ): EventListDto

    @POST("/v1/groups/{groupId}/events")
    suspend fun createEventSeries(
        @Path("groupId") groupId: String,
        @Body request: CreateSeriesRequest
    ): CreateSeriesResponseDto

    @GET("/v1/events/{id}")
    suspend fun getEventDetail(@Path("id") id: String): EventDetailDto

    @POST("/v1/events/{id}/rsvp")
    suspend fun submitRsvp(
        @Path("id") eventId: String,
        @Body request: RsvpRequestDto
    ): RsvpResponseDto

    @PATCH("/v1/event-series/{id}")
    suspend fun cancelEventSeries(
        @Path("id") seriesId: String,
        @Body request: UpdateSeriesRequestDto = UpdateSeriesRequestDto("CANCELLED")
    ): CancelSeriesResponseDto

    // 6. Async Matching
    @POST("/v1/match-requests")
    suspend fun createMatchRequest(@Body request: MatchRequestDto): MatchJobCreatedDto

    @GET("/v1/match-requests/{jobId}")
    suspend fun getMatchJob(@Path("jobId") jobId: String): MatchJobResponseDto

    @GET("/v1/match-invitations")
    suspend fun getMatchInvitations(@Query("status") status: String = "INVITED"): InvitationListDto

    @POST("/v1/match-invitations/{id}/accept")
    suspend fun acceptInvitation(@Path("id") invitationId: String): AcceptResponseDto

    @POST("/v1/match-invitations/{id}/decline")
    suspend fun declineInvitation(@Path("id") invitationId: String): DeclineResponseDto

    // 7. Realtime & Group Chat
    @POST("/v1/realtime/tickets")
    suspend fun issueWsTicket(@Body request: TicketRequestDto): TicketResponseDto

    @POST("/v1/groups/{groupId}/messages")
    suspend fun sendMessage(
        @Path("groupId") groupId: String,
        @Body request: SendMessageRequestDto
    ): SendMessageResponseDto

    @GET("/v1/groups/{groupId}/messages")
    suspend fun getMessages(
        @Path("groupId") groupId: String,
        @Query("before_seq") beforeSeq: Long? = null,
        @Query("limit") limit: Int = 50
    ): MessagePageDto

    // 8. Notifications
    @GET("/v1/notifications")
    suspend fun getNotifications(
        @Query("pageToken") pageToken: String? = null,
        @Query("limit") limit: Int = 20
    ): NotificationPageDto

    @GET("/v1/notifications/unread-count")
    suspend fun getUnreadNotificationCount(): UnreadCountDto

    @PATCH("/v1/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Response<Unit>

    @POST("/v1/notifications/read-all")
    suspend fun markAllNotificationsRead(): Response<Unit>

    // 9. Reviews & Reporting
    @POST("/v1/events/{occurrenceId}/reviews")
    suspend fun submitReview(
        @Path("occurrenceId") occurrenceId: String,
        @Body request: ReviewRequestDto
    ): ReviewViewDto

    @GET("/v1/groups/{groupId}/reviews")
    suspend fun getGroupReviews(@Path("groupId") groupId: String): ReviewSummaryDto

    @POST("/v1/reviews/{reviewId}/report")
    suspend fun reportReview(
        @Path("reviewId") reviewId: String,
        @Body request: ReportReviewRequestDto
    ): Response<Unit>

    // 10. Images
    @Multipart
    @POST("/v1/images")
    suspend fun uploadImage(
        @Query("type") type: String,
        @Query("groupId") groupId: String? = null,
        @Query("reviewId") reviewId: String? = null,
        @Part file: MultipartBody.Part
    ): ImageUploadResponseDto

    @GET("/v1/images/{id}")
    suspend fun getImage(@Path("id") id: String): Response<ResponseBody>

    @DELETE("/v1/images/{id}")
    suspend fun deleteImage(@Path("id") id: String): Response<Unit>
}

private val errorJson = Json { ignoreUnknownKeys = true }

fun Throwable.toKinshipApiException(): KinshipApiException {
    if (this is KinshipApiException) return this
    if (this is HttpException) {
        val status = code()
        val retryAfter = response()?.headers()?.get("Retry-After")?.toLongOrNull()
        val rawBody = try {
            response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        if (!rawBody.isNullOrBlank()) {
            try {
                val parsed = errorJson.decodeFromString<ApiErrorEnvelope>(rawBody)
                val detail = parsed.error
                if (detail != null) {
                    return KinshipApiException(status, detail.code, detail.message, retryAfter)
                }
            } catch (_: Exception) {
                // Non-JSON error body
            }
        }
        return KinshipApiException(status, "HTTP_$status", message() ?: "Request failed", retryAfter)
    }
    return KinshipApiException(0, "NETWORK_ERROR", localizedMessage ?: "Network unavailable")
}
