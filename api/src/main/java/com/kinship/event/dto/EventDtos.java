package com.kinship.event.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kinship.user.dto.LocationResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class EventDtos {

    private EventDtos() {
    }

    public record RecurrenceRequest(
            @NotBlank String freq,
            Integer interval,
            @Size(max = 3) List<String> byday,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
            @NotNull LocalDate startsOn,
            LocalDate until) {
    }

    public record CreateSeriesRequest(
            @Size(max = 120) String title,
            @Size(max = 2000) String description,
            @Min(30) @Max(480) Integer durationMin,
            @Min(1) @Max(100) Integer capacity,
            String timezone,
            @NotNull @Valid RecurrenceRequest recurrence,
            @Valid LocationRequest location) {
    }

    public record LocationRequest(
            @jakarta.validation.constraints.DecimalMin("-90") @jakarta.validation.constraints.DecimalMax("90") double lat,
            @jakarta.validation.constraints.DecimalMin("-180") @jakarta.validation.constraints.DecimalMax("180") double lng,
            String address) {
    }

    public record OccurrenceResponse(UUID eventId, Instant startTime, Instant endTime) {
    }

    public record CreateSeriesResponse(UUID eventSeriesId, Instant generatedThrough,
                                       List<OccurrenceResponse> occurrences) {
    }

    public record EventSummary(
            UUID eventId,
            UUID seriesId,
            String title,
            Instant startTime,
            Instant endTime,
            int capacity,
            int confirmedCount,
            String status,
            String myStatus) {
    }

    public record EventList(List<EventSummary> items) {
    }

    public record RsvpCounts(int going, int maybe, int declined, int notGoing) {
    }

    public record GroupRef(UUID groupId, String name, String visibility) {
    }

    public record EventDetailResponse(
            UUID eventId,
            UUID seriesId,
            String title,
            Instant startTime,
            Instant endTime,
            int capacity,
            int confirmedCount,
            String status,
            String myStatus,
            RsvpCounts rsvpCounts,
            GroupRef group,
            LocationResponse location) {
    }

    public record RsvpRequest(
            @NotBlank
            @Pattern(regexp = "GOING|NOT_GOING|MAYBE|DECLINED",
                    message = "status must be GOING, NOT_GOING, MAYBE or DECLINED")
            String status) {
    }

    public record RsvpResponse(UUID eventId, String status, int confirmedCount, int capacity) {
    }

    public record UpdateSeriesRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "CANCELLED",
                    message = "only CANCELLED is supported") String status) {
    }

    public record CancelSeriesResponse(int cancelledOccurrences) {
    }
}
