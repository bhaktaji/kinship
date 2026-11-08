package com.example.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorEnvelope(
    val error: ApiErrorDetail? = null
)

@Serializable
data class ApiErrorDetail(
    val code: String = "ERROR",
    val message: String = "Request failed",
    @SerialName("trace_id") val traceId: String? = null
)

class KinshipApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val retryAfterSeconds: Long? = null
) : Exception(message)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String,
    val timezone: String = "Europe/Berlin"
)

@Serializable
data class AuthResponse(
    val user: UserDto,
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 900
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 900
)

@Serializable
data class LocationDto(
    val lat: Double,
    val lng: Double,
    val address: String? = null
)

@Serializable
data class AvailabilitySlotDto(
    @SerialName("day_of_week") val dayOfWeek: Int,
    @SerialName("start_local") val startLocal: String,
    @SerialName("end_local") val endLocal: String
)

@Serializable
data class UserInterestDto(
    @SerialName("interest_id") val interestId: Int,
    val slug: String = "",
    val name: String = "",
    val weight: Double = 1.0
)

@Serializable
data class InterestSelectionDto(
    @SerialName("interest_id") val interestId: Int,
    val weight: Double = 1.0
)

@Serializable
data class InterestResponseDto(
    val id: Int,
    val slug: String,
    val name: String,
    val category: String
)

@Serializable
data class InterestsEnvelope(
    val items: List<InterestResponseDto> = emptyList()
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    val timezone: String = "Europe/Berlin",
    val location: LocationDto? = null,
    val availability: List<AvailabilitySlotDto> = emptyList(),
    val interests: List<UserInterestDto> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    val bio: String? = null,
    val hobbies: List<String> = emptyList(),
    val profession: String? = null,
    val workplace: String? = null,
    @SerialName("workplace_shared") val workplaceShared: Boolean = false,
    @SerialName("profile_photo_asset_id") val profilePhotoAssetId: String? = null,
    @SerialName("onboarding_completed") val onboardingCompleted: Boolean = false,
    @SerialName("onboarding_step") val onboardingStep: Int = 1
)

@Serializable
data class UpdateProfileRequest(
    @SerialName("display_name") val displayName: String? = null,
    val timezone: String? = null,
    val location: LocationDto? = null,
    val availability: List<AvailabilitySlotDto>? = null,
    val interests: List<InterestSelectionDto>? = null,
    val bio: String? = null,
    val hobbies: List<String>? = null,
    val profession: String? = null,
    val workplace: String? = null,
    @SerialName("workplace_shared") val workplaceShared: Boolean? = null,
    @SerialName("profile_photo_asset_id") val profilePhotoAssetId: String? = null,
    @SerialName("onboarding_completed") val onboardingCompleted: Boolean? = null
)

@Serializable
data class NextEventDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("start_time") val startTime: String
)

@Serializable
data class ActivityCardDto(
    @SerialName("activity_id") val activityId: String,
    val title: String,
    val category: String,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val location: LocationDto,
    @SerialName("distance_m") val distanceM: Int = 0,
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("max_members") val maxMembers: Int,
    @SerialName("next_event") val nextEvent: NextEventDto
)

@Serializable
data class DiscoveryPageDto(
    val items: List<ActivityCardDto> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null
)

@Serializable
data class OrganizationSummaryDto(
    val id: String,
    val name: String,
    val type: String? = null
)

@Serializable
data class GroupSummaryDto(
    @SerialName("group_id") val groupId: String,
    val name: String,
    val visibility: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("max_members") val maxMembers: Int,
    @SerialName("next_event") val nextEvent: NextEventDto? = null
)

@Serializable
data class ActivityDetailDto(
    @SerialName("activity_id") val activityId: String,
    val title: String,
    val description: String? = null,
    val category: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val location: LocationDto,
    val organization: OrganizationSummaryDto? = null,
    val groups: List<GroupSummaryDto> = emptyList()
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val category: String,
    @SerialName("activity_id") val activityId: String? = null,
    val location: LocationDto? = null,
    @SerialName("max_members") val maxMembers: Int = 6,
    val visibility: String = "public",
    @SerialName("join_policy") val joinPolicy: String = "OPEN"
)

@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    val description: String? = null,
    @SerialName("max_members") val maxMembers: Int? = null,
    val visibility: String? = null,
    @SerialName("join_policy") val joinPolicy: String? = null,
    @SerialName("banner_asset_id") val bannerAssetId: String? = null
)

@Serializable
data class GroupDto(
    @SerialName("group_id") val groupId: String,
    val name: String,
    val description: String? = null,
    val category: String,
    @SerialName("activity_id") val activityId: String? = null,
    val location: LocationDto,
    @SerialName("max_members") val maxMembers: Int,
    val visibility: String = "public",
    @SerialName("join_policy") val joinPolicy: String = "OPEN",
    val status: String = "ACTIVE",
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("active_member_count") val activeMemberCount: Int = 1,
    @SerialName("my_role") val myRole: String? = null,
    @SerialName("my_status") val myStatus: String? = null
)

@Serializable
data class GroupMemberDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    val status: String,
    @SerialName("joined_at") val joinedAt: String = "",
    @SerialName("profile_photo_asset_id") val profilePhotoAssetId: String? = null
)

@Serializable
data class EventSummaryDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("series_id") val seriesId: String? = null,
    val title: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val capacity: Int,
    @SerialName("confirmed_count") val confirmedCount: Int,
    val status: String = "SCHEDULED",
    @SerialName("my_status") val myStatus: String? = null
)

@Serializable
data class GroupDetailDto(
    val group: GroupDto,
    val members: List<GroupMemberDto> = emptyList(),
    @SerialName("next_events") val nextEvents: List<EventSummaryDto> = emptyList()
)

@Serializable
data class GroupCardDto(
    @SerialName("group_id") val groupId: String,
    val name: String,
    val category: String,
    val visibility: String,
    @SerialName("max_members") val maxMembers: Int,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("my_status") val myStatus: String = "ACTIVE",
    @SerialName("next_event_id") val nextEventId: String? = null,
    @SerialName("next_event_at") val nextEventAt: String? = null
)

@Serializable
data class GroupPageDto(
    val items: List<GroupCardDto> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null
)

@Serializable
data class JoinResponseDto(
    @SerialName("group_id") val groupId: String,
    val status: String
)

@Serializable
data class JoinRequestCreateDto(
    val reason: String,
    @SerialName("share_workplace") val shareWorkplace: Boolean = false
)

@Serializable
data class JoinRequestResponseDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("group_id") val groupId: String,
    val status: String
)

@Serializable
data class JoinRequestViewDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    val reason: String,
    @SerialName("share_workplace") val shareWorkplace: Boolean = false,
    @SerialName("display_name") val displayName: String,
    val bio: String? = null,
    val profession: String? = null,
    val workplace: String? = null,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class MembershipUpdateRequest(
    val status: String
)

@Serializable
data class RecurrenceRequestDto(
    val freq: String,
    val interval: Int = 1,
    val byday: List<String>? = null,
    @SerialName("start_time") val startTime: String,
    @SerialName("starts_on") val startsOn: String,
    val until: String? = null
)

@Serializable
data class CreateSeriesRequest(
    val title: String,
    val description: String? = null,
    @SerialName("duration_min") val durationMin: Int = 90,
    val capacity: Int? = null,
    val timezone: String = "Europe/Berlin",
    val recurrence: RecurrenceRequestDto,
    val location: LocationDto? = null
)

@Serializable
data class OccurrenceResponseDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String
)

@Serializable
data class CreateSeriesResponseDto(
    @SerialName("event_series_id") val eventSeriesId: String,
    @SerialName("generated_through") val generatedThrough: String = "",
    val occurrences: List<OccurrenceResponseDto> = emptyList()
)

@Serializable
data class EventListDto(
    val items: List<EventSummaryDto> = emptyList()
)

@Serializable
data class RsvpCountsDto(
    val going: Int = 0,
    val maybe: Int = 0,
    val declined: Int = 0,
    @SerialName("not_going") val notGoing: Int = 0
)

@Serializable
data class GroupRefDto(
    @SerialName("group_id") val groupId: String,
    val name: String,
    val visibility: String
)

@Serializable
data class EventDetailDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("series_id") val seriesId: String? = null,
    val title: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val capacity: Int,
    @SerialName("confirmed_count") val confirmedCount: Int,
    val status: String = "SCHEDULED",
    @SerialName("my_status") val myStatus: String? = null,
    @SerialName("rsvp_counts") val rsvpCounts: RsvpCountsDto = RsvpCountsDto(),
    val group: GroupRefDto,
    val location: LocationDto
)

@Serializable
data class RsvpRequestDto(
    val status: String
)

@Serializable
data class RsvpResponseDto(
    @SerialName("event_id") val eventId: String,
    val status: String,
    @SerialName("confirmed_count") val confirmedCount: Int,
    val capacity: Int
)

@Serializable
data class UpdateSeriesRequestDto(
    val status: String = "CANCELLED"
)

@Serializable
data class CancelSeriesResponseDto(
    @SerialName("cancelled_occurrences") val cancelledOccurrences: Int
)

@Serializable
data class MatchRequestDto(
    @SerialName("max_distance_km") val maxDistanceKm: Int = 10,
    @SerialName("preferred_group_size") val preferredGroupSize: Int = 4
)

@Serializable
data class MatchJobCreatedDto(
    @SerialName("job_id") val jobId: String,
    val status: String
)

@Serializable
data class MemberPreviewDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val interests: List<String> = emptyList(),
    @SerialName("distance_km") val distanceKm: Double = 0.0
)

@Serializable
data class MatchResultDto(
    @SerialName("match_group_id") val matchGroupId: String,
    val status: String,
    val score: Double = 0.0,
    @SerialName("expires_at") val expiresAt: String,
    val members: List<MemberPreviewDto> = emptyList()
)

@Serializable
data class MatchJobResponseDto(
    @SerialName("job_id") val jobId: String,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
    val results: List<MatchResultDto> = emptyList()
)

@Serializable
data class MatchInvitationDto(
    @SerialName("invitation_id") val invitationId: String,
    @SerialName("match_group_id") val matchGroupId: String,
    @SerialName("expires_at") val expiresAt: String,
    val status: String,
    val members: List<MemberPreviewDto> = emptyList()
)

@Serializable
data class InvitationListDto(
    val items: List<MatchInvitationDto> = emptyList()
)

@Serializable
data class AcceptResponseDto(
    val status: String,
    @SerialName("group_id") val groupId: String? = null
)

@Serializable
data class DeclineResponseDto(
    val status: String
)

@Serializable
data class SendMessageRequestDto(
    val body: String,
    @SerialName("client_msg_id") val clientMsgId: String
)

@Serializable
data class SendMessageResponseDto(
    @SerialName("message_id") val messageId: String,
    @SerialName("sequence_id") val sequenceId: Long,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class ChatMessageDto(
    @SerialName("message_id") val messageId: String,
    @SerialName("sequence_id") val sequenceId: Long,
    @SerialName("sender_id") val senderId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class MessagePageDto(
    val items: List<ChatMessageDto> = emptyList(),
    @SerialName("next_before_seq") val nextBeforeSeq: Long? = null
)

@Serializable
data class TicketRequestDto(
    @SerialName("group_id") val groupId: String
)

@Serializable
data class TicketResponseDto(
    val ticket: String,
    @SerialName("expires_in") val expiresIn: Long = 60
)

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    val title: String,
    val body: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class NotificationPageDto(
    val items: List<NotificationDto> = emptyList(),
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("next_page_token") val nextPageToken: String? = null
)

@Serializable
data class UnreadCountDto(
    @SerialName("unread_count") val unreadCount: Int = 0
)

@Serializable
data class ReviewRequestDto(
    @SerialName("overall_rating") val overallRating: Int,
    @SerialName("atmosphere_rating") val atmosphereRating: Int,
    @SerialName("organization_rating") val organizationRating: Int,
    val feedback: String? = null,
    @SerialName("vibe_tags") val vibeTags: List<String> = emptyList(),
    @SerialName("photo_asset_ids") val photoAssetIds: List<String> = emptyList()
)

@Serializable
data class ReviewViewDto(
    val id: String,
    @SerialName("occurrence_id") val occurrenceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("overall_rating") val overallRating: Int,
    @SerialName("atmosphere_rating") val atmosphereRating: Int,
    @SerialName("organization_rating") val organizationRating: Int,
    val feedback: String? = null,
    @SerialName("vibe_tags") val vibeTags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("edited_until") val editedUntil: String = ""
)

@Serializable
data class ReviewSummaryDto(
    val count: Int = 0,
    @SerialName("avg_overall") val avgOverall: Double? = null,
    @SerialName("avg_atmosphere") val avgAtmosphere: Double? = null,
    @SerialName("avg_organization") val avgOrganization: Double? = null,
    val recent: List<ReviewViewDto> = emptyList()
)

@Serializable
data class ReportReviewRequestDto(
    val reason: String
)

@Serializable
data class ImageUploadResponseDto(
    @SerialName("image_id") val imageId: String
)
