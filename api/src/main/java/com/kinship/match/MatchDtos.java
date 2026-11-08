package com.kinship.match.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MatchDtos {

    private MatchDtos() {
    }

    public record MatchRequest(
            @Min(1) @Max(50) Integer maxDistanceKm,
            @Min(4) @Max(6) Integer preferredGroupSize) {
    }

    public record MatchJobCreated(UUID jobId, String status) {
    }

    public record MatchJobResponse(UUID jobId, String status, String errorCode, List<MatchResult> results) {
    }

    public record MatchResult(UUID matchGroupId, String status, double score, Instant expiresAt,
                              List<MemberPreview> members) {
    }

    public record MemberPreview(UUID userId, String displayName, List<String> interests, double distanceKm) {
    }

    public record InvitationList(List<InvitationResponse> items) {
    }

    public record InvitationResponse(UUID invitationId, UUID matchGroupId, Instant expiresAt,
                                     String status, List<MemberPreview> members) {
    }

    public record AcceptResponse(String status, UUID groupId) {
    }

    public record DeclineResponse(String status) {
    }

    public record Preferences(int maxDistanceKm, int preferredGroupSize) {
    }
}
