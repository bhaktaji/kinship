package com.kinship.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record AvailabilitySlot(
        @Min(value = 1, message = "day_of_week must be 1..7")
        @Max(value = 7, message = "day_of_week must be 1..7")
        int dayOfWeek,
        @NotNull(message = "start_local is required")
        @JsonFormat(pattern = "HH:mm") LocalTime startLocal,
        @NotNull(message = "end_local is required")
        @JsonFormat(pattern = "HH:mm") LocalTime endLocal) {
}
