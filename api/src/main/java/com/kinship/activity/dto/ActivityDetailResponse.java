package com.kinship.activity.dto;

import java.util.List;
import java.util.UUID;

public record ActivityDetailResponse(
        UUID activityId,
        String title,
        String description,
        String category,
        String imageUrl,
        ActivityLocation location,
        OrganizationSummary organization,
        List<GroupSummary> groups) {
}
