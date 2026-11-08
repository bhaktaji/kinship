package com.kinship.user.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String displayName,
        String timezone,
        LocationResponse location,
        List<AvailabilitySlot> availability,
        List<UserInterestResponse> interests,
        Instant createdAt,
        String bio,
        List<String> hobbies,
        String profession,
        String workplace,
        boolean workplaceShared,
        UUID profilePhotoAssetId,
        boolean onboardingCompleted,
        int onboardingStep) {
}