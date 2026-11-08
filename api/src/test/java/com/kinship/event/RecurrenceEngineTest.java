package com.kinship.event;

import com.kinship.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceEngineTest {

    private final RecurrenceEngine engine = new RecurrenceEngine();
    private final ZoneId berlin = ZoneId.of("Europe/Berlin");

    @Test
    void generatesWeeklyOccurrencesWithinHorizon() {
        LocalDate start = LocalDate.of(2026, 11, 5);
        var rule = new RecurrenceEngine.Rule(RecurrenceEngine.Freq.WEEKLY, 1,
                List.of(DayOfWeek.THURSDAY), LocalTime.of(18, 30), start, LocalDate.of(2026, 12, 31));
        List<Instant> result = engine.generate(rule, berlin, start.plusWeeks(8).atStartOfDay(berlin).toInstant());
        assertThat(result).hasSize(8);
        assertThat(result.getFirst()).isEqualTo(ZonedDateTime.of(start, LocalTime.of(18, 30), berlin).toInstant());
    }

    @Test
    void monthlySkipsFebruary29() {
        var rule = new RecurrenceEngine.Rule(RecurrenceEngine.Freq.MONTHLY, 1,
                List.of(), LocalTime.of(18, 0), LocalDate.of(2023, 1, 29), LocalDate.of(2023, 5, 1));
        List<Instant> result = engine.generate(rule, berlin, LocalDate.of(2023, 5, 2).atStartOfDay(berlin).toInstant());
        assertThat(result).hasSize(3); // Jan 29, Mar 29, Apr 29 (2023 has no Feb 29)
    }

    @Test
    void preservesWallTimeAcrossDstSpringForward() {
        var rule = new RecurrenceEngine.Rule(RecurrenceEngine.Freq.WEEKLY, 1,
                List.of(DayOfWeek.SUNDAY), LocalTime.of(2, 30), LocalDate.of(2026, 3, 22),
                LocalDate.of(2026, 4, 5));
        List<Instant> result = engine.generate(rule, berlin, LocalDate.of(2026, 4, 6).atStartOfDay(berlin).toInstant());
        assertThat(result).hasSize(3);
        assertThat(result.get(1).atZone(berlin).getHour()).isEqualTo(3);
    }

    @Test
    void rejectsMonthlyByDay() {
        assertThatThrownBy(() -> engine.validate(new RecurrenceEngine.Rule(RecurrenceEngine.Freq.MONTHLY, 1,
                List.of(DayOfWeek.MONDAY), LocalTime.NOON, LocalDate.of(2026, 1, 1), null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsIntervalOtherThanOne() {
        assertThatThrownBy(() -> engine.validate(new RecurrenceEngine.Rule(RecurrenceEngine.Freq.WEEKLY, 2,
                List.of(DayOfWeek.MONDAY), LocalTime.NOON, LocalDate.of(2026, 1, 1), null)))
                .isInstanceOf(ApiException.class);
    }
}
