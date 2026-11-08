package com.kinship.user;

import java.time.Instant;
import java.util.UUID;

public record UserRecord(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        String timezone,
        Double lat,
        Double lng,
        String availabilityJson,
        String status,
        Instant createdAt,
        String bio,
        String hobbiesJson,
        String profession,
        String workplace,
        boolean workplaceShared,
        UUID profilePhotoAssetId,
        boolean onboardingCompleted,
        int onboardingStep) {
}