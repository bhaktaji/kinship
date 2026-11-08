package com.kinship.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateProfileRequest(
        @Size(min = 2, max = 50, message = "display_name must be 2..50 characters")
        String displayName,
        String timezone,
        @Valid LocationRequest location,
        @Size(max = 14, message = "at most 14 availability slots are allowed")
        List<@Valid AvailabilitySlot> availability,
        @Size(max = 30, message = "at most 30 interests are allowed")
        List<@Valid InterestSelection> interests,
        @Size(max = 500) String bio,
        @Size(max = 20) List<@Size(max = 40) String> hobbies,
        @Size(max = 120) String profession,
        @Size(max = 120) String workplace,
        Boolean workplaceShared,
        UUID profilePhotoAssetId,
        Boolean onboardingCompleted) {
}