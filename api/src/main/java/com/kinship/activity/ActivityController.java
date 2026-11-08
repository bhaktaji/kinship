package com.kinship.activity;

import com.kinship.activity.dto.ActivityDetailResponse;
import com.kinship.activity.dto.DiscoveryPage;
import com.kinship.common.api.ApiException;
import com.kinship.common.ratelimit.RateLimitService;
import com.kinship.common.security.UserPrincipal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/activities")
@Validated
public class ActivityController {

    private final ActivityService activityService;
    private final RateLimitService rateLimit;

    public ActivityController(ActivityService activityService, RateLimitService rateLimit) {
        this.activityService = activityService;
        this.rateLimit = rateLimit;
    }

    @GetMapping
    public ResponseEntity<DiscoveryPage> discover(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam(name = "radius_km", defaultValue = "10") @DecimalMin("1.0") @DecimalMax("50.0") double radiusKm,
            @RequestParam(required = false) String category,
            @RequestParam(name = "starts_after", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startsAfter,
            @RequestParam(name = "group_size_max", required = false) @Min(2) @Max(20) Integer groupSizeMax,
            @RequestParam(name = "page_token", required = false) String pageToken,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        rateLimit.check("disc:" + principal.id(), 120, Duration.ofMinutes(1));
        String normalizedToken = pageToken == null || pageToken.isBlank() ? null : pageToken;
        DiscoveryResult result = activityService.discover(new DiscoveryQuery(lat, lng, radiusKm,
                activityService.normalizeCategory(category), startsAfter, groupSizeMax, normalizedToken, limit));
        return ResponseEntity.ok()
                .header("X-Cache", result.cacheHit() ? "HIT" : "MISS")
                .body(new DiscoveryPage(result.items(), result.nextPageToken()));
    }

    @GetMapping("/{id}")
    public ActivityDetailResponse detail(@PathVariable UUID id) {
        return activityService.detail(id);
    }
}
