package com.kinship.activity.dto;

import java.util.UUID;

public record GroupSummary(
        UUID groupId,
        String name,
        String visibility,
        int memberCount,
        int maxMembers,
        NextEvent nextEvent) {
}
