package com.kinship.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.activity.H3Service;
import com.kinship.common.api.ApiException;
import com.kinship.user.dto.AvailabilitySlot;
import com.kinship.user.dto.InterestResponse;
import com.kinship.user.dto.LocationResponse;
import com.kinship.user.dto.UpdateProfileRequest;
import com.kinship.user.dto.UserInterestResponse;
import com.kinship.user.dto.UserProfileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository users;
    private final H3Service h3;
    private final ObjectMapper objectMapper;

    public UserService(UserRepository users, H3Service h3, ObjectMapper objectMapper) {
        this.users = users;
        this.h3 = h3;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse profile(UUID userId) {
        UserRecord user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "User not found"));
        return toProfile(user);
    }

    @Transactional
    public UserProfileResponse createUser(String email, String passwordHash, String displayName, String timezone) {
        String zone = normalizeZone(timezone);
        UUID id = UUID.randomUUID();
        users.insert(id, email.toLowerCase(), passwordHash, displayName.trim(), zone);
        return profile(id);
    }

    @Transactional
    public UserProfileResponse update(UUID userId, UpdateProfileRequest request) {
        UserRecord current = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "User not found"));

        if (request.timezone() != null) {
            normalizeZone(request.timezone());
        }
        validateAvailability(request.availability());
        validateInterests(request.interests());

        users.updateBasics(userId, request.displayName(), request.timezone());
        if (request.location() != null) {
            users.updateLocation(userId, request.location().lat(), request.location().lng(),
                    h3.cell(request.location().lat(), request.location().lng()));
        }
        if (request.availability() != null) {
            users.updateAvailability(userId, writeJson(request.availability()));
        }
        if (request.interests() != null) {
            users.replaceInterests(userId, request.interests());
        }

        UserRecord refreshed = users.findById(userId).orElse(current);
        boolean completed = isOnboardingComplete(refreshed, request);
        int step = Boolean.TRUE.equals(request.onboardingCompleted()) ? 4 : refreshed.onboardingStep();
        users.updateExtended(userId,
                request.bio(),
                request.hobbies() == null ? null : writeStringList(request.hobbies()),
                request.profession(),
                request.workplace(),
                request.workplaceShared(),
                request.profilePhotoAssetId(),
                completed || Boolean.TRUE.equals(request.onboardingCompleted()),
                completed ? Math.max(step, 4) : step);
        return toProfile(users.findById(userId).orElse(refreshed));
    }

    private boolean isOnboardingComplete(UserRecord user, UpdateProfileRequest request) {
        String displayName = request.displayName() != null ? request.displayName() : user.displayName();
        if (displayName == null || displayName.isBlank()) {
            return false;
        }
        LocationResponse location = user.lat() == null || user.lng() == null ? null : new LocationResponse(user.lat(), user.lng());
        if (request.location() != null) {
            location = new LocationResponse(request.location().lat(), request.location().lng());
        }
        if (location == null) {
            return false;
        }
        List<AvailabilitySlot> availability = request.availability() != null ? request.availability() : readAvailability(user.availabilityJson());
        if (availability == null || availability.isEmpty()) {
            return false;
        }
        List<UserInterestResponse> interests = user.id() == null ? List.of() : users.findUserInterests(user.id()).stream()
                .map(i -> new UserInterestResponse(i.interestId(), i.slug(), i.name(), i.weight())).toList();
        // interests must exist; reuse current if not changed
        if (interests.isEmpty() && (request.interests() == null || request.interests().isEmpty())) {
            return false;
        }
        List<String> hobbies = request.hobbies() != null ? request.hobbies() : readStringList(user.hobbiesJson());
        if (hobbies == null || hobbies.isEmpty()) {
            return false;
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<InterestResponse> interests() {
        return users.findAllInterests().stream()
                .map(i -> new InterestResponse(i.id(), i.slug(), i.name(), i.category()))
                .toList();
    }

    private void validateAvailability(List<AvailabilitySlot> slots) {
        if (slots == null) {
            return;
        }
        Set<Integer> days = new HashSet<>();
        for (AvailabilitySlot slot : slots) {
            if (!slot.startLocal().isBefore(slot.endLocal())) {
                throw ApiException.badRequest("VALIDATION_ERROR",
                        "availability start_local must be before end_local");
            }
            days.add(slot.dayOfWeek());
        }
    }

    private void validateInterests(List<com.kinship.user.dto.InterestSelection> selections) {
        if (selections == null) {
            return;
        }
        Set<Integer> known = users.findAllInterests().stream().map(InterestRecord::id).collect(java.util.stream.Collectors.toSet());
        for (var selection : selections) {
            if (!known.contains(selection.interestId())) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        "Unknown interest", Map.of("interest_id", selection.interestId()));
            }
        }
    }

    private String normalizeZone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? "UTC" : timezone;
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Invalid timezone", Map.of("timezone", value));
        }
    }

    private UserProfileResponse toProfile(UserRecord user) {
        List<AvailabilitySlot> availability = readAvailability(user.availabilityJson());
        List<UserInterestResponse> interests = users.findUserInterests(user.id()).stream()
                .map(i -> new UserInterestResponse(i.interestId(), i.slug(), i.name(), i.weight()))
                .toList();
        LocationResponse location = user.lat() == null || user.lng() == null
                ? null : new LocationResponse(user.lat(), user.lng());
        return new UserProfileResponse(user.id(), user.email(), user.displayName(), user.timezone(),
                location, availability, interests, user.createdAt(),
                user.bio(), readStringList(user.hobbiesJson()), user.profession(), user.workplace(),
                user.workplaceShared(), user.profilePhotoAssetId(), user.onboardingCompleted(), user.onboardingStep());
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeStringList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialise list", e);
        }
    }

    private List<AvailabilitySlot> readAvailability(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AvailabilitySlot>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(List<AvailabilitySlot> slots) {
        try {
            return objectMapper.writeValueAsString(slots);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialise availability", e);
        }
    }
}
