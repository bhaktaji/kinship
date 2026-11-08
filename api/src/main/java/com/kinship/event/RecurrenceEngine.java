package com.kinship.event;

import com.kinship.common.api.ApiException;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RecurrenceEngine {

    public enum Freq {
        WEEKLY, BIWEEKLY, MONTHLY
    }

    public record Rule(Freq freq, int interval, List<DayOfWeek> byDays, LocalTime startTime,
                       LocalDate startsOn, LocalDate until) {
    }

    public List<Instant> generate(Rule rule, ZoneId zone, Instant horizon) {
        validate(rule);
        LocalDate horizonDate = horizon.atZone(zone).toLocalDate();
        LocalDate limit = rule.until() == null || rule.until().isAfter(horizonDate) ? horizonDate : rule.until();
        if (rule.startsOn().isAfter(limit)) {
            return List.of();
        }
        List<Instant> result = rule.freq() == Freq.MONTHLY
                ? monthly(rule, zone, horizon, limit)
                : weekly(rule, zone, horizon, limit);
        return result.stream().distinct().sorted(Comparator.naturalOrder()).toList();
    }

    public void validate(Rule rule) {
        if (rule.freq() == null || rule.interval() != 1 || rule.startTime() == null
                || rule.startsOn() == null) {
            throw ApiException.badRequest("UNSUPPORTED_RECURRENCE", "Unsupported recurrence rule");
        }
        if (rule.until() != null && rule.until().isBefore(rule.startsOn())) {
            throw ApiException.badRequest("UNSUPPORTED_RECURRENCE", "until must be on or after starts_on");
        }
        if (rule.freq() == Freq.MONTHLY) {
            if (rule.byDays() != null && !rule.byDays().isEmpty()) {
                throw ApiException.badRequest("UNSUPPORTED_RECURRENCE", "MONTHLY does not accept byday");
            }
            return;
        }
        if (rule.byDays() == null || rule.byDays().isEmpty() || rule.byDays().size() > 3
                || new LinkedHashSet<>(rule.byDays()).size() != rule.byDays().size()) {
            throw ApiException.badRequest("UNSUPPORTED_RECURRENCE", "byday is required for weekly recurrence");
        }
    }

    public static DayOfWeek parseDay(String value) {
        String day = value.trim().toUpperCase(Locale.ROOT);
        return switch (day) {
            case "MON", "MONDAY" -> DayOfWeek.MONDAY;
            case "TUE", "TUESDAY" -> DayOfWeek.TUESDAY;
            case "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "THU", "THURSDAY" -> DayOfWeek.THURSDAY;
            case "FRI", "FRIDAY" -> DayOfWeek.FRIDAY;
            case "SAT", "SATURDAY" -> DayOfWeek.SATURDAY;
            case "SUN", "SUNDAY" -> DayOfWeek.SUNDAY;
            default -> throw ApiException.badRequest("UNSUPPORTED_RECURRENCE", "Invalid byday: " + value);
        };
    }

    private List<Instant> weekly(Rule rule, ZoneId zone, Instant horizon, LocalDate limit) {
        Set<Instant> result = new LinkedHashSet<>();
        for (DayOfWeek day : rule.byDays()) {
            LocalDate date = rule.startsOn().with(TemporalAdjusters.nextOrSame(day));
            while (!date.isAfter(limit)) {
                Instant instant = ZonedDateTime.of(date, rule.startTime(), zone).toInstant();
                if (!instant.isAfter(horizon)) {
                    result.add(instant);
                }
                date = date.plusWeeks(rule.interval());
            }
        }
        return new ArrayList<>(result);
    }

    private List<Instant> monthly(Rule rule, ZoneId zone, Instant horizon, LocalDate limit) {
        List<Instant> result = new ArrayList<>();
        YearMonth month = YearMonth.from(rule.startsOn());
        int dayOfMonth = rule.startsOn().getDayOfMonth();
        while (true) {
            if (month.atDay(1).isAfter(limit)) {
                break;
            }
            if (dayOfMonth <= month.lengthOfMonth()) {
                LocalDate date = month.atDay(dayOfMonth);
                if (!date.isBefore(rule.startsOn()) && !date.isAfter(limit)) {
                    Instant instant = ZonedDateTime.of(date, rule.startTime(), zone).toInstant();
                    if (!instant.isAfter(horizon)) {
                        result.add(instant);
                    }
                }
            }
            month = month.plusMonths(rule.interval());
            if (month.atDay(1).isAfter(limit)) {
                break;
            }
        }
        return result;
    }
}
