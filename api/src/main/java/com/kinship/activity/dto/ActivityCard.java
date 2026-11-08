package com.kinship.activity.dto;

import java.util.UUID;

public record ActivityCard(
        UUID activityId,
        String title,
        String category,
        String description,
        String imageUrl,
        ActivityLocation location,
        long distanceM,
        UUID groupId,
        String groupName,
        int memberCount,
        int maxMembers,
        NextEvent nextEvent) {
}
