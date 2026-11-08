package com.kinship.review;

import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import com.kinship.event.EventRepository;
import com.kinship.event.dto.EventDtos.EventDetailResponse;
import com.kinship.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReviewService {
    private final ReviewRepository reviews;
    private final EventRepository events;
    private final NotificationService notifications;

    public ReviewService(ReviewRepository reviews, EventRepository events, NotificationService notifications) {
        this.reviews = reviews;
        this.events = events;
        this.notifications = notifications;
    }

    @Transactional
    public ReviewView submit(UUID userId, UUID occurrenceId, ReviewRequest request) {
        EventDetailResponse event = events.findEvent(occurrenceId, userId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Occurrence not found"));
        if (!"GOING".equals(event.myStatus())) {
            throw ApiException.forbidden("NOT_ATTENDEE", "Only verified attendees can review");
        }
        if (!event.endTime().isBefore(Instant.now())) {
            throw ApiException.conflict("OCCURRENCE_NOT_ENDED", "Reviews open after the event ends");
        }
        if (request.overallRating() < 1 || request.overallRating() > 5 ||
                request.atmosphereRating() < 1 || request.atmosphereRating() > 5 ||
                request.organizationRating() < 1 || request.organizationRating() > 5) {
            throw ApiException.badRequest("VALIDATION_ERROR", "ratings must be 1..5");
        }
        var existing = reviews.findByOccurrenceAndUser(occurrenceId, userId);
        Instant editedUntil = Instant.now().plusSeconds(7L * 24 * 3600);
        String tagsJson = writeTags(request.vibeTags());
        if (existing.isPresent()) {
            ReviewRepository.ReviewRecord r = existing.get();
            if (Instant.now().isAfter(r.editedUntil())) {
                throw ApiException.conflict("EDIT_WINDOW_CLOSED", "Review can no longer be edited");
            }
            reviews.update(r.id(), request.overallRating(), request.atmosphereRating(), request.organizationRating(),
                    request.feedback(), tagsJson);
            return toView(reviews.findByOccurrenceAndUser(occurrenceId, userId).orElse(r));
        }
        UUID id = reviews.insert(occurrenceId, event.group().groupId(), userId, request.overallRating(),
                request.atmosphereRating(), request.organizationRating(), request.feedback(), tagsJson, editedUntil);
        notifications.notify(userId, "REVIEW_RECORDED", "Review submitted", "Thanks for your feedback", "EVENT", occurrenceId);
        return toView(reviews.findByOccurrenceAndUser(occurrenceId, userId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public ReviewSummaryView summary(UUID groupId) {
        ReviewRepository.ReviewSummary s = reviews.summaryByGroup(groupId);
        List<ReviewView> recent = reviews.listByGroup(groupId, 10).stream().map(this::toView).toList();
        return new ReviewSummaryView(s.count(), s.count() == 0 ? null : s.avgOverall(),
                s.count() == 0 ? null : s.avgAtmosphere(), s.count() == 0 ? null : s.avgOrganization(), recent);
    }

    @Transactional
    public void report(UUID userId, UUID reviewId, String reason) {
        reviews.report(reviewId, userId, reason);
    }

    private ReviewView toView(ReviewRepository.ReviewRecord r) {
        return new ReviewView(r.id(), r.occurrenceId(), r.userId(), r.overallRating(), r.atmosphereRating(),
                r.organizationRating(), r.feedback(), readTags(r.vibeTagsJson()), r.createdAt(), r.updatedAt(), r.editedUntil());
    }

    private String writeTags(List<String> tags) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readTags(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    public record ReviewRequest(int overallRating, int atmosphereRating, int organizationRating, String feedback,
                                 List<String> vibeTags, List<String> photoAssetIds) {
    }

    public record ReviewView(UUID id, UUID occurrenceId, UUID userId, int overallRating, int atmosphereRating,
                             int organizationRating, String feedback, List<String> vibeTags, Instant createdAt,
                             Instant updatedAt, Instant editedUntil) {
    }

    public record ReviewSummaryView(int count, Double avgOverall, Double avgAtmosphere, Double avgOrganization,
                                    List<ReviewView> recent) {
    }
}