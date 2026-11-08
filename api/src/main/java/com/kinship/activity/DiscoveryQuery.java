package com.kinship.activity;

import java.time.Instant;

public record DiscoveryQuery(
        double lat,
        double lng,
        double radiusKm,
        String category,
        Instant startsAfter,
        Integer groupSizeMax,
        String pageToken,
        int limit) {
}
