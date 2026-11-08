package com.kinship.activity;

import java.util.List;

public final class RadiusBuckets {

    private static final List<Integer> BUCKETS = List.of(2, 5, 10, 20, 50);

    private RadiusBuckets() {
    }

    public static int of(double radiusKm) {
        for (int bucket : BUCKETS) {
            if (radiusKm <= bucket) {
                return bucket;
            }
        }
        return 50;
    }
}
