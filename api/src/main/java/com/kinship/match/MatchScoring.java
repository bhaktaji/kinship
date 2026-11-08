package com.kinship.match;

import com.kinship.user.dto.AvailabilitySlot;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class MatchScoring {

    private MatchScoring() {
    }

    public static double jaccard(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    public static int overlapMinutes(List<AvailabilitySlot> a, List<AvailabilitySlot> b) {
        int minutes = 0;
        for (AvailabilitySlot left : a) {
            for (AvailabilitySlot right : b) {
                if (left.dayOfWeek() != right.dayOfWeek()) {
                    continue;
                }
                var start = left.startLocal().isAfter(right.startLocal())
                        ? left.startLocal() : right.startLocal();
                var end = left.endLocal().isBefore(right.endLocal())
                        ? left.endLocal() : right.endLocal();
                if (end.isAfter(start)) {
                    minutes += (int) Duration.between(start, end).toMinutes();
                }
            }
        }
        return minutes;
    }

    public static double availabilityScore(List<AvailabilitySlot> a, List<AvailabilitySlot> b) {
        return Math.min(1.0, overlapMinutes(a, b) / 180.0);
    }

    public static double score(Set<Integer> interestsA, Set<Integer> interestsB,
                               List<AvailabilitySlot> availabilityA, List<AvailabilitySlot> availabilityB,
                               double distanceKm, double maxDistanceKm, int sharedGroups) {
        double distance = maxDistanceKm <= 0 ? 0 : Math.max(0, 1 - distanceKm / maxDistanceKm);
        double repeat = Math.min(1.0, 0.3 * sharedGroups);
        double value = 0.4 * jaccard(interestsA, interestsB)
                + 0.3 * availabilityScore(availabilityA, availabilityB)
                + 0.2 * distance
                - 0.3 * repeat;
        return Math.max(0, Math.min(1, value));
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double radius = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
