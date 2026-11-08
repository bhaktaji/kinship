package com.kinship.match;

import com.kinship.user.dto.AvailabilitySlot;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MatchScoringTest {

    @Test
    void jaccardUsesIntersectionOverUnion() {
        assertThat(MatchScoring.jaccard(Set.of(1, 2, 3), Set.of(2, 3, 4))).isEqualTo(0.5);
        assertThat(MatchScoring.jaccard(Set.of(1), Set.of(1))).isEqualTo(1.0);
        assertThat(MatchScoring.jaccard(Set.of(), Set.of())).isZero();
    }

    @Test
    void availabilityOverlapIsCappedAtThreeHours() {
        var a = List.of(new AvailabilitySlot(4, LocalTime.of(18, 0), LocalTime.of(21, 0)));
        var b = List.of(new AvailabilitySlot(4, LocalTime.of(19, 0), LocalTime.of(22, 0)));
        assertThat(MatchScoring.overlapMinutes(a, b)).isEqualTo(120);
        assertThat(MatchScoring.availabilityScore(a, b)).isEqualTo(120.0 / 180.0);
    }

    @Test
    void scoreIsClampedAndPenalisesRepeatedGroups() {
        var availability = List.of(new AvailabilitySlot(4, LocalTime.of(18, 0), LocalTime.of(21, 0)));
        double clean = MatchScoring.score(Set.of(1, 2, 3), Set.of(1, 2, 3), availability, availability,
                0, 10, 0);
        double repeated = MatchScoring.score(Set.of(1, 2, 3), Set.of(1, 2, 3), availability, availability,
                0, 10, 2);
        assertThat(clean).isBetween(0.0, 1.0);
        assertThat(repeated).isLessThan(clean);
        assertThat(clean).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void haversineApproximatesKnownDistance() {
        assertThat(MatchScoring.haversineKm(52.5200, 13.4050, 52.5200, 13.4050)).isZero();
        assertThat(MatchScoring.haversineKm(52.5200, 13.4050, 52.5300, 13.4050)).isBetween(1.0, 1.2);
    }
}
