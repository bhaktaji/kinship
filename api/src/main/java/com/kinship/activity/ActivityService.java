package com.kinship.activity;

import com.kinship.activity.dto.ActivityCard;
import com.kinship.activity.dto.ActivityDetailResponse;
import com.kinship.common.api.ApiException;
import com.kinship.common.ops.KinshipMetrics;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ActivityService {

    private static final double EARTH_KM_PER_DEGREE = 111.32;

    private final ActivityRepository activities;
    private final DiscoveryCache cache;
    private final CursorCodec cursorCodec;
    private final H3Service h3;
    private final KinshipMetrics metrics;

    public ActivityService(ActivityRepository activities, DiscoveryCache cache,
                           CursorCodec cursorCodec, H3Service h3, KinshipMetrics metrics) {
        this.activities = activities;
        this.cache = cache;
        this.cursorCodec = cursorCodec;
        this.h3 = h3;
        this.metrics = metrics;
    }

    public DiscoveryResult discover(DiscoveryQuery query) {
        if (query.category() != null && !activities.categoryExists(query.category())) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Unknown category");
        }
        int bucket = RadiusBuckets.of(query.radiusKm());
        String cell = h3.cell(query.lat(), query.lng());
        String cacheKey = "geo:" + cell + ":" + (query.category() == null ? "all" : query.category()) + ":" + bucket;

        boolean cacheHit = true;
        List<ActivityCard> cards = cache.get(cacheKey).orElse(null);
        if (cards == null) {
            cacheHit = false;
            cards = load(query, bucket);
            cache.put(cacheKey, cards);
        }

        metrics.discovery(cacheHit);

        String fingerprint = cursorCodec.fingerprint(query.lat(), query.lng(), query.radiusKm(),
                query.category(), query.startsAfter(), query.groupSizeMax());
        CursorCodec.Cursor cursor = query.pageToken() == null ? null
                : cursorCodec.decode(query.pageToken(), fingerprint);

        double requestedRadiusMeters = query.radiusKm() * 1000.0;
        List<ActivityCard> candidates = cards.stream()
                .filter(card -> card.distanceM() <= requestedRadiusMeters)
                .filter(card -> query.startsAfter() == null
                        || !card.nextEvent().startTime().isBefore(query.startsAfter()))
                .filter(card -> query.groupSizeMax() == null
                        || card.maxMembers() <= query.groupSizeMax())
                .filter(card -> cursor == null || after(card, cursor))
                .toList();

        List<ActivityCard> page = candidates.stream().limit(query.limit()).toList();
        String nextToken = candidates.size() > query.limit() && !page.isEmpty()
                ? cursorCodec.encode(fingerprint, page.get(page.size() - 1).distanceM(),
                        page.get(page.size() - 1).activityId())
                : null;
        return new DiscoveryResult(page, nextToken, cacheHit);
    }

    public ActivityDetailResponse detail(UUID activityId) {
        return activities.findDetail(activityId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Activity not found"));
    }

    private List<ActivityCard> load(DiscoveryQuery query, int bucket) {
        double radiusKm = bucket;
        double[] box = boundingBox(query.lat(), query.lng(), radiusKm);
        return activities.findNearby(query.lat(), query.lng(), radiusKm * 1000.0,
                box[0], box[1], box[2], box[3], query.category());
    }

    private boolean after(ActivityCard card, CursorCodec.Cursor cursor) {
        if (card.distanceM() > cursor.distanceM()) {
            return true;
        }
        return card.distanceM() == cursor.distanceM()
                && card.activityId().compareTo(cursor.activityId()) > 0;
    }

    private double[] boundingBox(double lat, double lng, double radiusKm) {
        double latDelta = radiusKm / EARTH_KM_PER_DEGREE;
        double lngDelta = radiusKm / (EARTH_KM_PER_DEGREE
                * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        double minLat = Math.max(-90.0, lat - latDelta);
        double maxLat = Math.min(90.0, lat + latDelta);
        double minLng = Math.max(-180.0, lng - lngDelta);
        double maxLng = Math.min(180.0, lng + lngDelta);
        return new double[]{minLat, minLng, maxLat, maxLng};
    }

    public String normalizeCategory(String category) {
        return category == null || category.isBlank() ? null : category.trim().toLowerCase(Locale.ROOT);
    }
}
