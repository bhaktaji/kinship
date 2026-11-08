package com.kinship.activity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RadiusBucketsTest {

    @Test
    void picksSmallestBucketAtOrAboveRadius() {
        assertThat(RadiusBuckets.of(1)).isEqualTo(2);
        assertThat(RadiusBuckets.of(3)).isEqualTo(5);
        assertThat(RadiusBuckets.of(5)).isEqualTo(5);
        assertThat(RadiusBuckets.of(6)).isEqualTo(10);
        assertThat(RadiusBuckets.of(20)).isEqualTo(20);
        assertThat(RadiusBuckets.of(21)).isEqualTo(50);
        assertThat(RadiusBuckets.of(99)).isEqualTo(50);
    }
}
