package com.kinship.activity;

import com.kinship.activity.dto.ActivityCard;

import java.util.List;

public record DiscoveryResult(List<ActivityCard> items, String nextPageToken, boolean cacheHit) {
}
