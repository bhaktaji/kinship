package com.kinship.activity.dto;

import java.time.Instant;
import java.util.UUID;

public record NextEvent(UUID eventId, Instant startTime) {
}
