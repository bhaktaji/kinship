package com.kinship.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

@Service
public class OccurrenceGenerator {

    public static final Duration HORIZON = Duration.ofDays(56);
    private static final Logger log = LoggerFactory.getLogger(OccurrenceGenerator.class);

    private final EventRepository events;
    private final RecurrenceEngine recurrence;
    private final TransactionTemplate transaction;

    public OccurrenceGenerator(EventRepository events, RecurrenceEngine recurrence,
                               PlatformTransactionManager transactionManager) {
        this.events = events;
        this.recurrence = recurrence;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public int extendSeries(EventRepository.SeriesRow series, Instant now) {
        ZoneId zone = ZoneId.of(series.timezone());
        Instant horizon = now.plus(HORIZON);
        RecurrenceEngine.Rule rule = events.rule(series);
        List<Instant> starts = recurrence.generate(rule, zone, horizon);
        int inserted = 0;
        for (Instant start : starts) {
            Instant end = start.plus(Duration.ofMinutes(series.durationMin()));
            inserted += events.insertOccurrence(series, start, end);
        }
        events.updateGeneratedThrough(series.id(), horizon);
        return inserted;
    }

    @Scheduled(cron = "0 15 2 * * *")
    public void extendDue() {
        int total = 0;
        for (EventRepository.SeriesRow series : events.dueSeries(200)) {
            try {
                total += transaction.execute(status -> extendSeries(series, Instant.now()));
            } catch (RuntimeException e) {
                log.error("occurrence_generation_failed series_id={} reason={}", series.id(),
                        e.getClass().getSimpleName());
            }
        }
        log.info("occurrence_generation_complete inserted={}", total);
    }
}
