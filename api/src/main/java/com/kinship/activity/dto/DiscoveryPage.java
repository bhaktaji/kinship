package com.kinship.activity.dto;

import java.util.List;

public record DiscoveryPage(List<ActivityCard> items, String nextPageToken) {
}
