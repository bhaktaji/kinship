package com.kinship.group.dto;

import com.kinship.event.dto.EventDtos.EventSummary;
import com.kinship.user.dto.LocationRequest;
import com.kinship.user.dto.LocationResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GroupDtos {

    private GroupDtos() {
    }

    public record CreateGroupRequest(
            @NotBlank @Size(min = 3, max = 80) String name,
            @Size(max = 2000) String description,
            @NotBlank String category,
            UUID activityId,
            @Valid LocationRequest location,
            @Min(2) @Max(20) Integer maxMembers,
            @Pattern(regexp = "public|private", message = "visibility must be public or private") String visibility,
            @Pattern(regexp = "OPEN|APPROVAL", message = "join_policy must be OPEN or APPROVAL") String joinPolicy) {
    }

    public record UpdateGroupRequest(
            @Size(min = 3, max = 80) String name,
            @Size(max = 2000) String description,
            @Min(2) @Max(20) Integer maxMembers,
            @Pattern(regexp = "public|private", message = "visibility must be public or private") String visibility,
            @Pattern(regexp = "OPEN|APPROVAL", message = "join_policy must be OPEN or APPROVAL") String joinPolicy,
            java.util.UUID bannerAssetId) {
    }

    public record JoinResponse(UUID groupId, String status) {
    }

    public record MembershipUpdateRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|REMOVED", message = "status must be ACTIVE or REMOVED") String status) {
    }

    public record MemberResponse(UUID userId, String displayName, String role, String status, Instant joinedAt,
                                 UUID profilePhotoAssetId) {
    }

    public record GroupResponse(
            UUID groupId,
            String name,
            String description,
            String category,
            UUID activityId,
            LocationResponse location,
            int maxMembers,
            String visibility,
            String status,
            String joinPolicy,
            UUID createdBy,
            Instant createdAt,
            int activeMemberCount,
            String myRole,
            String myStatus) {
    }

    public record GroupDetailResponse(GroupResponse group, List<MemberResponse> members,
                                      List<EventSummary> nextEvents) {
    }

    public record GroupCard(
            UUID groupId,
            String name,
            String category,
            String visibility,
            int maxMembers,
            int memberCount,
            String myStatus,
            UUID nextEventId,
            Instant nextEventAt) {
    }

    public record GroupPage(List<GroupCard> items, String nextPageToken) {
    }

    public record JoinRequestCreateRequest(
            @NotBlank @Size(min = 1, max = 500) String reason,
            Boolean shareWorkplace) {
    }

    public record RejectRequest(@Size(max = 500) String reason) {
    }

    public record JoinRequestResponse(UUID requestId, UUID groupId, String status) {
    }

    public record JoinRequestView(
            UUID requestId, UUID groupId, UUID userId, String reason, boolean shareWorkplace,
            String displayName, String bio, String hobbiesJson, String profession, String workplace,
            UUID profilePhotoAssetId, String availabilityJson, java.time.Instant createdAt) {
    }
}
